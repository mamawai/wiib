package com.mawai.wiibsim.campaign.service;

import com.mawai.wiibcommon.exception.BizException;
import com.mawai.wiibsim.campaign.entity.Campaign;
import com.mawai.wiibsim.campaign.entity.CampaignVote;
import com.mawai.wiibsim.campaign.mapper.CampaignVoteMapper;
import com.mawai.wiibsim.campaign.model.VoteBoard;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.*;

/**
 * 每日多空投票。
 * <p>
 * 【为什么固定 BTC + 黄金】两者常反向，有讨论价值；标的固定也让"明天投什么"能提前一晚开始聊。
 * <p>
 * 【为什么用 UTC 日】规则是"UTC 0 点前投票，按日线收盘 vs 前日收盘结算"，
 * 而 Binance 的 1d K 线就是 UTC 日切。投票日与结算依据必须同一个时区，否则边界那一票永远对不上。
 * 这与签到刻意不同 —— 签到日是服务器本地日（见 {@link CampaignCheckinService}），两个口径别混。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CampaignVoteService {

    /** 标的 → 展示名。顺序即前端卡片顺序 */
    public static final Map<String, String> SYMBOLS = new LinkedHashMap<>() {{
        put(CampaignVote.SYMBOL_BTC, "BTC");
        put(CampaignVote.SYMBOL_GOLD, "黄金");
    }};

    private final CampaignVoteMapper voteMapper;
    private final CampaignService campaignService;

    /** 当前 UTC 交易日 */
    public static LocalDate utcToday() {
        return LocalDate.now(ZoneOffset.UTC);
    }

    /**
     * 投票。多空二选一由唯一索引 (campaign,user,date,symbol) 保证 —— 选了多就插不进空。
     */
    public void vote(Long userId, String symbol, String direction) {
        Campaign c = campaignService.requireRunning();
        if (!SYMBOLS.containsKey(symbol)) throw new BizException("不支持的投票标的");
        if (!CampaignVote.UP.equals(direction) && !CampaignVote.DOWN.equals(direction)) {
            throw new BizException("方向只能是 UP 或 DOWN");
        }

        CampaignVote v = new CampaignVote();
        v.setCampaignId(c.getId());
        v.setUserId(userId);
        v.setVoteDate(utcToday());
        v.setSymbol(symbol);
        v.setDirection(direction);
        try {
            voteMapper.insert(v);
        } catch (DuplicateKeyException e) {
            throw new BizException("今天已经投过 " + SYMBOLS.get(symbol) + " 了，多空二选一");
        }
    }

    /**
     * 今日票况：两个标的各一条。
     * <p>
     * 【这里用 current() 不用 requireRunning()】看板是读路径，开赛前要能展示"两边都是 0 票"、
     * 收摊后要能展示最后一天的票况；判了窗口这两段时间前端就只剩报错。
     */
    public List<VoteBoard> board(Long userId) {
        Campaign c = campaignService.current();
        if (c == null) return List.of();
        LocalDate today = utcToday();

        Map<String, String> mine = new HashMap<>();
        for (CampaignVote v : voteMapper.listMine(c.getId(), userId, today)) {
            mine.put(v.getSymbol(), v.getDirection());
        }

        List<VoteBoard> out = new ArrayList<>(SYMBOLS.size());
        for (Map.Entry<String, String> e : SYMBOLS.entrySet()) {
            String symbol = e.getKey();
            // GROUP BY 最多两行，某方向一票没有就压根不出行 —— 所以起手是 0，不是等 SQL 给
            long up = 0, down = 0;
            for (Map<String, Object> row : voteMapper.countByDirection(c.getId(), today, symbol)) {
                long cnt = ((Number) row.get("cnt")).longValue();
                if (CampaignVote.UP.equals(row.get("direction"))) up = cnt; else down = cnt;
            }
            out.add(new VoteBoard(symbol, e.getValue(), up, down, mine.get(symbol)));
        }
        return out;
    }
}
