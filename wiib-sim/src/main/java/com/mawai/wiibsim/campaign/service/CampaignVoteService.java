package com.mawai.wiibsim.campaign.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.mawai.wiibcommon.exception.BizException;
import com.mawai.wiibcommon.market.BinanceRestClient;
import com.mawai.wiibsim.campaign.entity.Campaign;
import com.mawai.wiibsim.campaign.entity.CampaignVote;
import com.mawai.wiibsim.campaign.mapper.CampaignVoteMapper;
import com.mawai.wiibsim.campaign.model.VoteBoard;
import com.mawai.wiibsim.campaign.model.VoteTally;
import com.mawai.wiibsim.campaign.score.ScoreRules;
import com.mawai.wiibsim.campaign.score.VoteScorer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
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
    private final BinanceRestClient binanceRestClient;

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

    // ==================== 结算 ====================

    /**
     * 结算某个 UTC 交易日的投票。幂等：回填走 CAS（result IS NULL），重跑不会覆盖已发的分，
     * 也不会重复发放。任何一天漏结算了，隔天补跑即可。
     * <p>
     * 【可分池靠反推不靠存状态】pool = 100 × (从活动首日到该日的天数) − 全场已发出的分。
     * 平盘顺延、没人猜对、封顶剩下的，全都自动包含在这个差里 ——
     * 不必额外存一个"顺延余额"，也就不存在那个数被 Redis 清掉或与真值漂移的问题。
     * <p>
     * 【这里用 current() 不用 requireRunning()】结算跑在 UTC 00:05、结的是<b>前一天</b>，
     * 活动最后一天的票要在 endAt 之后才结得上；判了窗口最后一天的票永远发不出分。
     */
    public void settleDay(LocalDate utcDay) {
        Campaign c = campaignService.current();
        if (c == null) return;

        List<CampaignVote> votes = voteMapper.listUnsettled(c.getId(), utcDay);
        if (votes.isEmpty()) {
            log.info("活动投票结算：{} 无待结算票", utcDay);
            return;
        }

        // 每个标的当日的涨跌：日线收盘 vs 前日收盘
        Map<String, String> outcome = new HashMap<>();
        for (String symbol : SYMBOLS.keySet()) {
            String o = resolveOutcome(symbol, utcDay);
            if (o == null) {
                log.warn("活动投票结算：{} 取不到 {} 的日线，本日整体推迟结算", utcDay, symbol);
                return;   // 拿不到价就整天不结算，下次任务重跑；绝不用残缺数据发分
            }
            outcome.put(symbol, o);
        }

        // 先判每票输赢，再按赢家均分
        Map<Long, Integer> correct = new LinkedHashMap<>();
        Map<Long, List<CampaignVote>> winners = new LinkedHashMap<>();
        List<CampaignVote> losers = new ArrayList<>();
        List<CampaignVote> deferred = new ArrayList<>();

        for (CampaignVote v : votes) {
            String o = outcome.get(v.getSymbol());
            if (CampaignVote.DEFERRED.equals(o)) {
                deferred.add(v);
            } else if (o.equals(v.getDirection())) {
                correct.merge(v.getUserId(), 1, Integer::sum);
                winners.computeIfAbsent(v.getUserId(), k -> new ArrayList<>()).add(v);
            } else {
                losers.add(v);
            }
        }

        BigDecimal pool = poolOf(c, utcDay);
        VoteTally tally = VoteScorer.allocate(correct, pool);

        // 一个人当日的分摊到他那几张赢票上：末票兜差额，保证逐票之和等于该人应得
        winners.forEach((userId, list) -> {
            BigDecimal total = tally.awarded().getOrDefault(userId, BigDecimal.ZERO);
            BigDecimal each = total.divide(BigDecimal.valueOf(list.size()), 2, RoundingMode.DOWN);
            BigDecimal used = BigDecimal.ZERO;
            for (int i = 0; i < list.size(); i++) {
                BigDecimal s = (i == list.size() - 1) ? total.subtract(used) : each;
                used = used.add(s);
                voteMapper.settle(list.get(i).getId(), CampaignVote.WIN, s);
            }
        });
        losers.forEach(v -> voteMapper.settle(v.getId(), CampaignVote.LOSE, BigDecimal.ZERO));
        deferred.forEach(v -> voteMapper.settle(v.getId(), CampaignVote.DEFERRED, BigDecimal.ZERO));

        log.info("活动投票结算完成 {}：池={} 赢家={}人 顺延={}", utcDay, pool, winners.size(), tally.carryOver());
    }

    /** 当日可分池 = 100 × 已过天数 − 全场已发出的分；下限 0 */
    private BigDecimal poolOf(Campaign c, LocalDate utcDay) {
        long days = ChronoUnit.DAYS.between(c.getStartAt().toLocalDate(), utcDay) + 1;
        BigDecimal entitled = ScoreRules.VOTE_DAILY_POOL.multiply(BigDecimal.valueOf(Math.max(days, 1)));
        return entitled.subtract(voteMapper.sumAllScore(c.getId())).max(BigDecimal.ZERO);
    }

    /**
     * 该 UTC 日的涨跌：日线收盘 vs 前日收盘。平盘返回 DEFERRED；取不到价返回 null。
     * <p>
     * 【为什么走 Binance 而不是库里的 kline_history】那张表只落 5m（quant 回测用），
     * 且不覆盖 XAUUSDT。Binance 的 1d K 线本身就是 UTC 日切，与投票日同一时区，边界不会错位；
     * 而且它是不变的历史数据，隔几天补结算也拿得到同样的值。
     */
    private String resolveOutcome(String symbol, LocalDate utcDay) {
        try {
            long endMs = utcDay.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() - 1;
            JSONArray rows = JSON.parseArray(binanceRestClient.getFuturesKlinesLight(symbol, "1d", 3, endMs));
            if (rows == null || rows.size() < 2) return null;

            BigDecimal close = closeOf(rows.getJSONArray(rows.size() - 1));
            BigDecimal prevClose = closeOf(rows.getJSONArray(rows.size() - 2));
            if (close == null || prevClose == null) return null;

            int cmp = close.compareTo(prevClose);
            if (cmp > 0) return CampaignVote.UP;
            if (cmp < 0) return CampaignVote.DOWN;
            return CampaignVote.DEFERRED;
        } catch (Exception e) {
            log.warn("活动投票结算：取 {} {} 日线失败: {}", symbol, utcDay, e.getMessage());
            return null;
        }
    }

    /**
     * 收盘价固定在下标 4。
     * <p>
     * 【实测结论·下标没被重排】getFuturesKlinesLight 里的精简是
     * {@code for (int j = 0; j <= 7; j++) slim.add(kline.get(j))}（BinanceRestClient.getSlimKlines），
     * 只把 Binance 原始 12 元组尾部的 8-11（笔数/taker 量额/保留位）裁掉，
     * 前 8 位<b>原序原位</b>保留：[openTime, open, high, low, close, volume, closeTime, quoteVolume]。
     * 所以每行 8 个元素，close 仍在 4 —— 与原始下标一致，那边的注释也是这么写的
     * （"前端蜡烛图按 Binance 原始下标取 k[5]=量、k[7]=额"）。
     * 精简若抛异常它会回退返回 12 元的原始串，close 同样在 4，两条路都对。
     */
    private static BigDecimal closeOf(JSONArray row) {
        return (row == null || row.size() < 5) ? null : row.getBigDecimal(4);
    }

    /** 全场投票分：userId → 累计得分 */
    public Map<Long, BigDecimal> voteScoreByUser(Long campaignId) {
        Map<Long, BigDecimal> out = new HashMap<>();
        for (Map<String, Object> row : voteMapper.sumScoreByUser(campaignId)) {
            out.put(((Number) row.get("user_id")).longValue(), (BigDecimal) row.get("total"));
        }
        return out;
    }
}
