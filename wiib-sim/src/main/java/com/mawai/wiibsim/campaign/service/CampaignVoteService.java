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
                // 两个方向各判一次而不是 else 兜底：手工塞库塞出第三种方向时，宁可这票不显示，
                // 也不能把它算到看跌那一栏上去
                if (CampaignVote.UP.equals(row.get("direction"))) up = cnt;
                else if (CampaignVote.DOWN.equals(row.get("direction"))) down = cnt;
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
     * 【可分池靠反推不靠存状态】pool = 100 × (从活动首日到该日的天数) − <b>截至该日</b>已发出的分。
     * 平盘顺延、没人猜对、封顶剩下的，全都自动包含在这个差里 ——
     * 不必额外存一个"顺延余额"，也就不存在那个数被 Redis 清掉或与真值漂移的问题。
     * 减数为什么必须卡在"截至该日"而不是全场，见 {@link #poolOf} 的注释：不卡的话补跑漏结的那天会全员 0 分。
     * <p>
     * 【这里用 current() 不用 requireRunning()】结算跑在 UTC 00:05、结的是<b>前一天</b>，
     * 活动最后一天的票要在 endAt 之后才结得上；判了窗口最后一天的票永远发不出分。
     * <p>
     * 【只结已经过完的 UTC 日】当天那根日线还在长，而且 UTC 0 点前都还能投票 ——
     * 拿半根蜡烛去结一批没截止的票，CAS 一落就改不回来了。
     */
    public void settleDay(LocalDate utcDay) {
        if (!utcDay.isBefore(utcToday())) {
            log.info("活动投票结算：{} 还没过完，不结", utcDay);
            return;
        }

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
            // 库里出了 SYMBOLS 之外的 symbol 时这里 NPE —— 是有意留响的。
            // vote() 已把 symbol 白名单校验过，唯一入口不产生这种行；真出现了（手工塞库）
            // 宁可炸出来记进日志，也不能用 Objects.equals 把它悄悄判成 LOSE 并标成已结算 ——
            // CAS 之后就再也纠不回来了。别"顺手修"成 Objects.equals。
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

    /**
     * 当日可分池 = 100 × 已过天数 − <b>截至这一天</b>已发出的分；下限 0。
     * <p>
     * 【减的是"截至这天"而不是"全场"】不卡日期的话这个式子只在按日期顺序结算时才成立。
     * 举个真会发生的例子：08-05 那次 00:05 没跑成（宿主重启 / 发版窗口），
     * 08-06、08-07 照常结完、累计已发到了 400，这时才回头补 08-05 ——
     * {@code 300 − 400} 是负数，被夹到 0，那天所有猜对的人集体拿 0 分，而 CAS 让这事不可逆。
     * 卡上日期后，每天的池只跟它自己和它之前的日子有关，隔多久补跑结果都一样，
     * 方法头上"漏结算了隔天补跑即可"这句话才是真的。顺序结算时两种写法逐位相同
     * （后面的日子还没结，本来就没分可加），所以正常链路上这个改动是零影响的。
     * <p>
     * 【乱序结算会让总额略微超发，这是有意的取舍】还是上面那个例子：08-06 结算时看到的顺延
     * 里含着本该属于 08-05 的那份，它已经花掉了；08-05 补结时按自己的额度又发一次，
     * 两边加起来会超过 100×天数。但另一个选择是让补结的那天全员 0 分 ——
     * 宁可总额略超，也不能凭"谁先跑"决定用户有没有分。真正的防线是别让它乱序：
     * {@link com.mawai.wiibsim.campaign.CampaignTask} 每次从最老的一天往回扫，漏一天下一轮就自己补上了。
     * <p>
     * 【start_at 是本地日、utcDay 是 UTC 日，为什么不换算就直接减】口径确实不同：
     * start_at 存的是服务器本地墙上时间（{@link CampaignService#requireRunning()} 拿
     * {@code LocalDateTime.now()} 跟它比就是证据），容器 TZ 是 Asia/Singapore(+8)。
     * 种子活动 [SGT 08-03 00:00, SGT 08-17 00:00) 换成 UTC 是 [08-02 16:00, 08-16 16:00)，
     * 而投票日盖的是 UTC 日戳 —— 于是能投票的 UTC 日有 <b>15</b> 个（08-02 ~ 08-16），
     * 比 14 天的活动多一个。看着像会多发 100，实际不会，因为本式给的不是"这天的额度"，
     * 而是"到这天为止的累计额度 − 累计已发"，天数只抬上限、不发钱：
     * <pre>
     *   08-02（只有 SGT 首日 00:00-08:00 那 8 小时）days=0 → 被 max 抬成 1 → 上限 100
     *   08-03                                     days=1            → 上限 100  ← 与 08-02 共用这 100
     *   …
     *   08-16（最后一个能投票的 UTC 日）           days=14           → 上限 1400 = 14 × 100
     * </pre>
     * 08-03 算池时"截至 08-03 已发出的分"已经含了 08-02 发掉的部分，所以两天加起来最多发 100；
     * 末日上限正好 1400，一分不多、也没有剩在池里没人拿。边界只会把预算在相邻两天之间挪，
     * 不会凭空造出预算 —— 这正是"靠反推不存状态"换来的好处。{@code Math.max(days, 1)} 是承重的：
     * 去掉它 08-02 的上限就是 0，那 8 小时里投的票全发 0 分。
     * <p>
     * 【顺带一提：单人上限是 90 不是设计文档说的 84】总额那 1400 不受多出来的这天影响，但
     * {@link ScoreRules#VOTE_DAILY_CAP} 是按<b>天</b>封顶的，15 个可投票 UTC 日就是 6 × 15 = <b>90</b>，
     * 而设计文档给封顶找的理由写的是「两周投票最多贡献 84 分」(6 × 14)。
     * 差的这 6 分没人在代码里校验、也不影响总额守恒（池子始终只有 1400），
     * 写在这儿只是免得下一个人以为 84 是被强制执行的。
     * <p>
     * 【但这个"恰好没事"依赖 UTC 正偏移】若挪到负偏移时区（如 UTC-5），
     * 活动尾巴会溢到 UTC 08-17，末日算出 days=15 → 上限 1500，真会多发 100。
     * 迁时区的话这里得先把 start_at 按部署时区折成 UTC 日再减。
     */
    private BigDecimal poolOf(Campaign c, LocalDate utcDay) {
        long days = ChronoUnit.DAYS.between(c.getStartAt().toLocalDate(), utcDay) + 1;
        BigDecimal entitled = ScoreRules.VOTE_DAILY_POOL.multiply(BigDecimal.valueOf(Math.max(days, 1)));
        return entitled.subtract(voteMapper.sumScoreUpTo(c.getId(), utcDay)).max(BigDecimal.ZERO);
    }

    /**
     * 该 UTC 日的涨跌：日线收盘 vs 前日收盘。平盘、或该标的这天压根没开市，返回 DEFERRED；
     * 取不到价返回 null。
     * <p>
     * 【为什么走 Binance 而不是库里的 kline_history】那张表只落 5m（quant 回测用），
     * 且不覆盖 XAUUSDT。Binance 的 1d K 线本身就是 UTC 日切，与投票日同一时区，边界不会错位；
     * 而且它是不变的历史数据，隔几天补结算也拿得到同样的值。
     * <p>
     * 【拿到的必须是"我要的那天"】Binance 返回的是 endTime <b>之前</b>的最后几根，不是"这天的"。
     * 黄金这种非 24 小时品种当天没开市（周末）时，最后一根会是上一个交易日的 ——
     * 照着算等于拿上一交易日的涨跌判这一天，而且周六周日会共用周五那同一根，
     * 一次行情能让同一个方向赢两回。所以拿 openTime（下标 0，1d 线就是该 UTC 日 0 点）
     * 跟请求日对一次，对不上就说明这天没这个标的的交易日。
     * <p>
     * 【DEFERRED 与 null 是两回事，别合并】
     * DEFERRED = "这个标的这天没结果"，是市场的真实状态：该标的的票判平、不计分、不进当日分母，
     * 而<b>另一个标的照常结算</b>（BTC 是 24×7 的，不该被黄金休市拖住）。
     * null = "我没拿到数据"（网络挂了、返回的不是 K 线），此时哪个标的的输赢都不可信，
     * 调用方会整天不结算、等下次任务重跑。合并这两者是对称的两种错：
     * 把 DEFERRED 当 null，黄金一休市 BTC 的票就跟着永远发不出分；
     * 把 null 当 DEFERRED，一次网络抖动就把全天的票判成平盘落库，CAS 之后再也纠不回来。
     */
    private String resolveOutcome(String symbol, LocalDate utcDay) {
        try {
            long dayStartMs = utcDay.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
            long endMs = utcDay.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() - 1;
            JSONArray rows = JSON.parseArray(binanceRestClient.getFuturesKlinesLight(symbol, "1d", 3, endMs));
            if (rows == null || rows.size() < 2) return null;

            JSONArray lastRow = rows.getJSONArray(rows.size() - 1);
            Long openTime = openTimeOf(lastRow);
            if (openTime == null) return null;
            if (openTime.longValue() != dayStartMs) {
                log.info("活动投票结算：{} {} 当日无日线（最后一根 openTime={}），该标的判顺延",
                        symbol, utcDay, openTime);
                return CampaignVote.DEFERRED;
            }

            BigDecimal close = closeOf(lastRow);
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

    /** openTime 在下标 0（同样是 Binance 原始下标，见 {@link #closeOf}）；1d 线的它就是该 UTC 日 0 点 */
    private static Long openTimeOf(JSONArray row) {
        return (row == null || row.isEmpty()) ? null : row.getLong(0);
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
