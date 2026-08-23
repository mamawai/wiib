package com.mawai.wiibsim.campaign.service;

import com.mawai.wiibcommon.exception.BizException;
import com.mawai.wiibcommon.i18n.MessageCatalog;
import com.mawai.wiibsim.campaign.entity.Campaign;
import com.mawai.wiibsim.campaign.entity.CampaignReward;
import com.mawai.wiibsim.campaign.mapper.CampaignMapper;
import com.mawai.wiibsim.campaign.mapper.CampaignRewardMapper;
import com.mawai.wiibsim.campaign.mapper.CampaignVoteMapper;
import com.mawai.wiibsim.campaign.model.CampaignScore;
import com.mawai.wiibsim.campaign.model.SettlementBasis;
import com.mawai.wiibsim.campaign.score.ScoreRules;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 活动结算：把积分表定格成 campaign_reward，按最大余额法分配 LDC。
 * <p>
 * <b>写进这张表的每一行都是真金白银，且 CAS 之后不可逆。</b>三道前置闸
 * （活动结束了没、投票结完了没、榜是不是现算的）宁可拦错也不能放错。
 * <p>
 * 分母就是参与名单（listEligibleUsers 已按"linux_do_id 纯数字"筛过），
 * 每个人都收得到款；分母混进收不到款的人，池子会有一截永远发不出去。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CampaignSettleService {

    private final CampaignMapper campaignMapper;
    private final CampaignRewardMapper rewardMapper;
    private final CampaignVoteMapper voteMapper;
    private final CampaignService campaignService;
    private final CampaignScoreService scoreService;
    /** 结算是管理员操作，拦阻文案同样跟界面语言 */
    private final MessageCatalog messages;

    /**
     * 结算。幂等：已经结算过（有 reward 行）就直接返回条数，不重复生成。
     * 用 current() 不用 requireRunning()：结算跑在活动结束之后，该判的状态本方法自己判。
     * 整个方法一个事务：落一半的话幂等分支会把残局认成"已结算"，再点也补不上。
     *
     * @return 生成的 reward 条数
     */
    @Transactional(rollbackFor = Exception.class)
    public int settle() {
        Campaign c = campaignService.current();
        if (c == null) throw new BizException(messages.get("campaign.settle.noCampaign"));

        int existing = rewardMapper.countByCampaign(c.getId());
        if (existing > 0) {
            log.info("活动 {} 已结算过，{} 条奖励", c.getCode(), existing);
            return existing;
        }

        requireEnded(c);
        requireVotesSettled(c);

        // 必须先算榜再翻状态，且榜必须是现算的（见 settlementBasis()）
        SettlementBasis basis = scoreService.settlementBasis();
        if (basis.weights().isEmpty()) throw new BizException(messages.get("campaign.settle.noEligibleUsers"));

        Map<Long, BigDecimal> amounts = ScoreRules.largestRemainder(c.getPrizePool(), basis.weights());

        BigDecimal sum = BigDecimal.ZERO;
        int n = 0;
        for (CampaignScore s : basis.board()) {
            BigDecimal amount = amounts.get(s.userId());
            if (amount == null) continue;           // 不在名单里或 0 分，压根没参与分配
            // 0.00 不落行：分发接口不收 0 额，落行只多一个必然失败的领取按钮；跳过的都是 0，总额对账不受影响
            if (amount.signum() == 0) {
                log.info("活动 {} 结算：userId={} 分到 0.00，不落行", c.getCode(), s.userId());
                continue;
            }

            CampaignReward r = new CampaignReward();
            r.setCampaignId(c.getId());
            r.setUserId(s.userId());
            // 占位：真正的收款人身份在领取时由二次 OAuth 回填，这里先留空
            // （列是 NOT NULL，写空串不写 null）
            r.setLinuxDoId("");
            r.setUsername(s.username());
            r.setTradeScore(s.tradeScore());
            r.setDailyScore(s.dailyScore());
            r.setVoteScore(s.voteScore());
            r.setPenalty(s.penalty());
            r.setFinalScore(s.finalScore());
            r.setLdcAmount(amount);
            r.setStatus(CampaignReward.PENDING);
            r.setOutTradeNo(outTradeNo(c.getCode(), s.userId()));
            rewardMapper.insert(r);
            sum = sum.add(amount);
            n++;
        }

        // 落表总额必须逐分等于奖池，否则整笔回滚：筛选条件或分配算法哪天改错，在这里炸而不是把错额发出去；
        // 顺带挡住奖池带分以下位数的情况
        if (sum.compareTo(c.getPrizePool()) != 0) {
            throw new BizException(messages.get("campaign.settle.sumMismatch",
                    Map.of("sum", sum, "pool", c.getPrizePool())));
        }

        c.setStatus(Campaign.STATUS_SETTLING);
        campaignMapper.updateById(c);

        log.info("活动 {} 结算完成：{} 人，共 {} LDC（奖池 {}）", c.getCode(), n, sum, c.getPrizePool());
        return n;
    }

    /**
     * 商户单号：{@code WIIB_{campaignCode}_{userId}}。
     * <b>补发必须用同一个单号</b>——防重复发放全架在服务端对它的唯一索引上，换新单号会发双份；
     * 静态方法为了人工补发能原样重算出同一个串。
     */
    public static String outTradeNo(String campaignCode, Long userId) {
        return "WIIB_" + campaignCode + "_" + userId;
    }

    /**
     * 活动没结束就不许结算：中途点下去等于拿半场的分把整个奖池分完。
     * 真要提前结改 end_at 即可，这里不锁死路。
     */
    private void requireEnded(Campaign c) {
        if (LocalDateTime.now().isBefore(c.getEndAt())) {
            throw new BizException(messages.get("campaign.settle.notEndedYet", Map.of("endAt", c.getEndAt())));
        }
    }

    /**
     * 投票没结完就不许结算：未结算的票榜单按 0 计，不报错不少人，
     * 只会按少算的权重把钱发出去。
     * 最后一批票（投的是明天）要等活动结束次日的回扫才结得上（约 32 小时），
     * 结束后第一天点结算被拦是正常的；报出具体日期让运营分得清"再等一晚"还是"取日线卡住了"。
     */
    private void requireVotesSettled(Campaign c) {
        List<LocalDate> pending = voteMapper.listUnsettledDates(c.getId());
        if (!pending.isEmpty()) {
            throw new BizException(messages.get("campaign.settle.votesPending", Map.of("pending", pending)));
        }
    }
}
