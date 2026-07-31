package com.mawai.wiibsim.campaign.service;

import com.mawai.wiibcommon.exception.BizException;
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
 * <b>写进这张表的每一行都是真金白银，且 CAS 之后不可逆。</b>所以本类的三道前置闸
 * （活动结束了没、投票结完了没、榜是不是现算的）宁可拦错也不能放错 —— 拦错了运营等一会儿再点一次，
 * 放错了钱按错的权重发出去就再也收不回来。
 * <p>
 * 【分母就是参与名单】名单已在 CampaignStatsMapper.listEligibleUsers 里按
 * "linux_do_id 必须是纯数字"筛过一道，机器人、管理员引导号、邀请码注册用户都进不来，
 * 所以这里拿到的每个人都收得到款，不会产生永远发不出去的 PENDING。
 * <p>
 * 【分母若混进收不到款的人会发不完】示例：全站 4700 分里有 700 分属于收不到款的账号，
 * 按 4700 算只能发出 425.5，剩 74.5 卡在账上谁也拿不走。这正是名单规则要挡住的。
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

    /**
     * 结算。幂等：已经结算过（有 reward 行）就直接返回条数，不重复生成。
     * <p>
     * 【用 current() 不用 requireRunning()】结算跑在活动结束<b>之后</b>，
     * 而 requireRunning() 在 endAt 之后必抛（Task 5 收紧的）。该判的状态本方法自己判。
     * <p>
     * 【整个方法一个事务】要么全部 reward 落库 + 活动翻 SETTLING，要么一行不留。
     * 落了一半就翻状态的话，没落到的人永远领不到，而 countByCampaign 已经大于 0、幂等分支
     * 会认为"已经结算过了"，再点一次也补不上。
     *
     * @return 生成的 reward 条数
     */
    @Transactional(rollbackFor = Exception.class)
    public int settle() {
        Campaign c = campaignService.current();
        if (c == null) throw new BizException("没有可结算的活动");

        int existing = rewardMapper.countByCampaign(c.getId());
        if (existing > 0) {
            log.info("活动 {} 已结算过，{} 条奖励", c.getCode(), existing);
            return existing;
        }

        requireEnded(c);
        requireVotesSettled(c);

        // ★ 必须先算榜再翻状态，且榜必须是现算的 —— 两条理由都写在 settlementBasis() 头上
        SettlementBasis basis = scoreService.settlementBasis();
        if (basis.weights().isEmpty()) throw new BizException("没有符合领取条件的用户，无法结算");

        Map<Long, BigDecimal> amounts = ScoreRules.largestRemainder(c.getPrizePool(), basis.weights());

        BigDecimal sum = BigDecimal.ZERO;
        int n = 0;
        for (CampaignScore s : basis.board()) {
            BigDecimal amount = amounts.get(s.userId());
            if (amount == null) continue;           // 不在名单里或 0 分，压根没参与分配
            // 【0.00 不落行】权重悬殊时最大余额法会给末位分到 0.00。给他写一行，
            // 前端就多一个点了必然失败的领取按钮（分发接口不收 0 额），库里多一条永远 FAILED 的记录。
            // 本仓库既有"账本不记 0.00 变动"的约定（8c5d6c4），这里照办。
            // 不影响总额对账：跳过的都是 0，写出去的和仍然恰好等于奖池
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

        // 【落表总额必须逐分等于奖池，否则整笔回滚】今天这条是结构上成立的：largestRemainder
        // 保证各份之和恰好是奖池，而 amounts 的 key 全都出自 basis.weights()、后者又是从 board 筛的，
        // 所以这个循环不会漏掉任何一份（跳过的都是 0.00）。留这道闸是给以后改动用的 ——
        // 谁哪天动了筛选条件或分配算法，这里当场炸并回滚整个事务，而不是把错的金额发出去。
        // 它还顺带挡住"奖池带了分以下的位数"（NUMERIC(18,4) 存得下 500.0050，但分不到 0.01 上）
        if (sum.compareTo(c.getPrizePool()) != 0) {
            throw new BizException("结算总额 " + sum + " 与奖池 " + c.getPrizePool()
                    + " 不符，已回滚。奖池若带分以下的位数请先改成两位小数");
        }

        c.setStatus(Campaign.STATUS_SETTLING);
        campaignMapper.updateById(c);

        log.info("活动 {} 结算完成：{} 人，共 {} LDC（奖池 {}）", c.getCode(), n, sum, c.getPrizePool());
        return n;
    }

    /**
     * 商户单号：{@code WIIB_{campaignCode}_{userId}}。
     * <p>
     * <b>【补发必须用这个，绝不能另起一个新单号】</b>整套防重复发放架在服务端对这个单号的唯一索引上：
     * 同一个单号重发会撞唯一索引、被判成"此前已发放成功"，钱不会出去第二遍。
     * 换个新单号补发就绕过了这道锁 —— 上次其实发成功了（只是响应没读到、或幂等报错措辞变了被记成 FAILED）
     * 的那些人会拿到双份。做成静态方法就是为了让人工补发能原样重算出同一个串。
     */
    public static String outTradeNo(String campaignCode, Long userId) {
        return "WIIB_" + campaignCode + "_" + userId;
    }

    /**
     * 活动没结束就不许结算。
     * <p>
     * 【这道闸挡的是什么】结算把当下的积分表定格成钱发出去，一次性、不可逆。活动进行到一半点下去，
     * 就是拿半场的分把整个奖池分完，后半程再怎么打也兑不成 LDC 了。
     * <p>
     * 【为什么不是防御性冗余】/settle 是个管理员点得动的按钮，而 campaign 表刻意做成可运行时改的，
     * "先把日期挪好再结算"本来就是运营流程的一部分 —— 真要提前结，改 end_at 即可，这里不锁死路。
     */
    private void requireEnded(Campaign c) {
        if (LocalDateTime.now().isBefore(c.getEndAt())) {
            throw new BizException("活动还没结束（" + c.getEndAt() + "），现在结算等于拿半场的分把奖池分完");
        }
    }

    /**
     * ★ 投票没结完就不许结算。★
     * <p>
     * 【为什么这条最容易踩】未结算的票 score 是 NULL，榜单按 0 计
     * （CampaignVoteMapper.sumScoreByUser 不筛 result）。所以"票没结完"不会报错、不会少人，
     * 只会让那几个人的 vote_score 悄悄少一截，然后按少算的权重把钱发出去。
     * <p>
     * 【为什么活动一结束往往就是没结完的，而且要等一整天】票投的是<b>明天</b>
     * （CampaignVoteService.votingDate），最后一批票盖的戳是活动结束当天的 UTC 日 ——
     * 那一天在 endAt 之后才开始过；而 settleDay 只结<b>已经过完</b>的 UTC 日。
     * 种子活动为例：endAt = SGT 08-17 00:00（= UTC 08-16 16:00），最后一批票的 vote_date 是 UTC 08-17，
     * 它过完是 UTC 08-18 00:00，最近的一次回扫在 UTC 08-18 00:05 = <b>SGT 08-18 08:05</b>，
     * 距 endAt 约 <b>32 小时</b>（投当天那版是 8 小时，投明天把它推后了整整一天）。
     * 也就是说活动结束后的第一整天里点结算，这道闸必然拦下来，而拦下来正是对的 ——
     * 那时候少算的正是最后一天的投票分。运营别在活动刚收摊的当晚等着结算，第三天早上再来。
     * <p>
     * 【报出具体是哪几天】运营看见日期才分得清是"再等一晚就好"还是"某天一直取不到日线、得去查"。
     */
    private void requireVotesSettled(Campaign c) {
        List<LocalDate> pending = voteMapper.listUnsettledDates(c.getId());
        if (!pending.isEmpty()) {
            throw new BizException("还有投票没结算：" + pending
                    + "。等 UTC 00:05 的回扫把这几天结完再结算，否则这些票的分会按 0 计入");
        }
    }
}
