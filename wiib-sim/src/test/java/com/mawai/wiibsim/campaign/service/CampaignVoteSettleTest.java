package com.mawai.wiibsim.campaign.service;

import com.alibaba.fastjson2.JSONArray;
import com.mawai.wiibcommon.market.BinanceRestClient;
import com.mawai.wiibsim.campaign.entity.Campaign;
import com.mawai.wiibsim.campaign.entity.CampaignVote;
import com.mawai.wiibsim.campaign.mapper.CampaignMapper;
import com.mawai.wiibsim.campaign.mapper.CampaignVoteMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 投票结算。不起 Spring、不连库、<b>不联网</b>。
 * <p>
 * 【为什么行情客户端必须 mock】本机被 Binance 按地区拒（451），真调必然拿不到价；
 * 而且日线是外部世界的数，真调的话"今天 BTC 涨没涨"决定测试红绿，那不是测试。
 * 这里喂固定的 K 线串，把"涨/跌/平/取不到"四种局面都摆出来。
 * <p>
 * 【喂进去的串按实测形状造】{@code getFuturesKlinesLight} 只裁掉 Binance 原始 12 元组的
 * 8-11，前 8 位原序原位 —— 每行 8 个元素、收盘价在下标 4。造样本时 open/high/low
 * 故意填成与 close 无关的常量，实现要是读错下标，涨跌立刻变成平盘，用例就红。
 * <p>
 * 【CAS 幂等测不了】{@code settle} 的 {@code WHERE result IS NULL} 是数据库那一侧的事，
 * mock 的 mapper 只会照单全收 —— 那条由
 * {@link com.mawai.wiibsim.campaign.CampaignVoteSettleRealRunTest} 在真库上钉。
 * 本类管 Java 这一侧：谁赢谁输、每票分多少、拿不到价时是不是真的一行都不写。
 */
class CampaignVoteSettleTest {

    private static final long CAMPAIGN_ID = 7L;

    /** 活动首日，poolOf 的起算点 */
    private static final LocalDate DAY1 = LocalDate.of(2026, 8, 3);
    private static final LocalDate DAY2 = LocalDate.of(2026, 8, 4);

    private static final String BTC = CampaignVote.SYMBOL_BTC;
    private static final String GOLD = CampaignVote.SYMBOL_GOLD;

    private CampaignVoteMapper voteMapper;
    private CampaignMapper campaignMapper;
    private BinanceRestClient binance;
    private CampaignVoteService service;

    /** 手搓票的自增 id，逐票断言时靠它认行 */
    private long nextVoteId;

    @BeforeEach
    void setUp() {
        voteMapper = mock(CampaignVoteMapper.class);
        campaignMapper = mock(CampaignMapper.class);
        binance = mock(BinanceRestClient.class);
        service = new CampaignVoteService(voteMapper, new CampaignService(campaignMapper), binance);
        nextVoteId = 1;

        Campaign c = new Campaign();
        c.setId(CAMPAIGN_ID);
        c.setStartAt(DAY1.atStartOfDay());
        c.setEndAt(DAY1.plusDays(14).atStartOfDay());
        c.setStatus(Campaign.STATUS_RUNNING);
        when(campaignMapper.selectRunning()).thenReturn(c);

        // 默认全场一分没发出去过
        when(voteMapper.sumAllScore(CAMPAIGN_ID)).thenReturn(BigDecimal.ZERO);
    }

    // ==================== 涨 / 跌 / 平 ====================

    /**
     * 一个正常交易日：BTC 涨、黄金跌，猜对的按"池 ÷ 当日总正确票数"拿分，猜错的 0 分。
     * <p>
     * 【样本为什么凑成 18 张赢票】100 ÷ 18 = 5.5555…，每票 5.55 —— 既不触封顶（那样看不出均分），
     * 又必须向下取整（进位的话 18 × 5.56 = 100.08 就超发了）。剩的 0.10 进顺延，
     * 它是不是真留下来由 {@link #前一日全额顺延后次日池子变大()} 钉。
     * <p>
     * 【两个标的的方向刻意相反】实现里把某个标的的涨跌套到另一个标的上，输赢立刻全反。
     */
    @Test
    void 涨跌各半时赢家按池均分输家记零分() {
        upDay(BTC);
        downDay(GOLD);

        CampaignVote btcWin = vote(1L, BTC, CampaignVote.UP);
        CampaignVote btcLose = vote(2L, BTC, CampaignVote.DOWN);
        CampaignVote goldWin = vote(3L, GOLD, CampaignVote.DOWN);
        CampaignVote goldLose = vote(4L, GOLD, CampaignVote.UP);

        List<CampaignVote> votes = new ArrayList<>(List.of(btcWin, btcLose, goldWin, goldLose));
        for (long u = 5; u <= 20; u++) votes.add(vote(u, BTC, CampaignVote.UP));   // 再凑 16 张赢票

        unsettled(DAY1, votes);
        service.settleDay(DAY1);

        List<Settled> rows = settledRows();
        assertThat(rows).as("20 张票必须一张不漏地写回结果").hasSize(20);
        assertThat(rows).extracting(Settled::id, Settled::result, Settled::score)
                .contains(tuple(btcWin.getId(), CampaignVote.WIN, "5.55"),
                        tuple(goldWin.getId(), CampaignVote.WIN, "5.55"),
                        tuple(btcLose.getId(), CampaignVote.LOSE, "0"),
                        tuple(goldLose.getId(), CampaignVote.LOSE, "0"));
        assertThat(rows).filteredOn(r -> CampaignVote.WIN.equals(r.result()))
                .as("18 张赢票每张 5.55").hasSize(18)
                .allSatisfy(r -> assertThat(r.score()).isEqualTo("5.55"));
    }

    /**
     * 平盘：那个标的当天的票全判 DEFERRED、0 分，且<b>不计入当日正确票数</b> ——
     * 它们要是混进分母，另一个标的的赢家就被平盘票稀释了。
     * <p>
     * 这里黄金只有一张赢票，独吞整池 100 却只拿到封顶的 6，正说明分母是 1 不是 4。
     */
    @Test
    void 平盘标的整体顺延且不稀释另一标的的赢家() {
        flatDay(BTC);
        upDay(GOLD);

        CampaignVote btcUp = vote(1L, BTC, CampaignVote.UP);
        CampaignVote btcDown = vote(2L, BTC, CampaignVote.DOWN);
        CampaignVote btcUp2 = vote(3L, BTC, CampaignVote.UP);
        CampaignVote goldUp = vote(4L, GOLD, CampaignVote.UP);

        unsettled(DAY1, List.of(btcUp, btcDown, btcUp2, goldUp));
        service.settleDay(DAY1);

        assertThat(settledRows()).extracting(Settled::id, Settled::result, Settled::score)
                .containsExactlyInAnyOrder(
                        tuple(btcUp.getId(), CampaignVote.DEFERRED, "0"),
                        tuple(btcDown.getId(), CampaignVote.DEFERRED, "0"),
                        tuple(btcUp2.getId(), CampaignVote.DEFERRED, "0"),
                        tuple(goldUp.getId(), CampaignVote.WIN, "6.00"));
    }

    // ==================== 拿不到价 ====================

    /**
     * 取行情抛异常：整天一行都不写 —— 连价拿得到的那个标的的票也不许结。
     * <p>
     * 【为什么必须是"整天"】池子是两个标的共享的，只结 BTC 那一半就等于按错误的分母发分，
     * 而这笔分一旦落库，CAS 就再也纠不回来了。宁可这天先不结，下次任务重跑（日线是不变的历史数据）。
     */
    @Test
    void 取日线抛异常时整天一行都不写() {
        upDay(BTC);
        when(binance.getFuturesKlinesLight(eq(GOLD), any(), anyInt(), anyLong()))
                .thenThrow(new RuntimeException("451 restricted location"));

        unsettled(DAY1, List.of(vote(1L, BTC, CampaignVote.UP), vote(2L, GOLD, CampaignVote.UP)));
        service.settleDay(DAY1);

        verify(voteMapper, never()).settle(anyLong(), any(), any());
    }

    /** 只回一根日线（比不出"vs 前日收盘"）同样是拿不到价：一行都不写 */
    @Test
    void 日线不足两根时整天一行都不写() {
        upDay(BTC);
        when(binance.getFuturesKlinesLight(eq(GOLD), any(), anyInt(), anyLong()))
                .thenReturn(klines("100"));

        unsettled(DAY1, List.of(vote(1L, BTC, CampaignVote.UP), vote(2L, GOLD, CampaignVote.UP)));
        service.settleDay(DAY1);

        verify(voteMapper, never()).settle(anyLong(), any(), any());
    }

    /** 返回一串不是 K 线的垃圾（网关错误页之类）也得当"没拿到"，不能崩在解析里 */
    @Test
    void 返回垃圾数据时整天一行都不写() {
        upDay(BTC);
        when(binance.getFuturesKlinesLight(eq(GOLD), any(), anyInt(), anyLong()))
                .thenReturn("{\"code\":0,\"msg\":\"Service unavailable from a restricted location\"}");

        unsettled(DAY1, List.of(vote(1L, BTC, CampaignVote.UP), vote(2L, GOLD, CampaignVote.UP)));
        service.settleDay(DAY1);

        verify(voteMapper, never()).settle(anyLong(), any(), any());
    }

    // ==================== 逐票分摊 ====================

    /**
     * 一个人两张赢票：分摊到票上后逐票之和必须<b>恰好</b>等于他应得的那个数。
     * <p>
     * 【样本为什么凑出 5.01 这种奇数分】5.01 ÷ 2 除不尽（2.505），前票向下取整到 2.50、
     * 末票兜 2.51。要是两票都取 2.50，这人凭空少 1 分，campaign_vote 逐行加出来的总分
     * 与榜单上的总分对不上 —— 出争议时没法自证。
     * <p>
     * 凑法：活动首日（entitled 100）已发出 79.96 → 池 20.04；总正确票 8 → 每票 2.5050；
     * 双份 5.0100 未触封顶 6，落 5.01。
     */
    @Test
    void 一个人两张赢票分摊后逐票之和等于他应得() {
        upDay(BTC);
        downDay(GOLD);
        when(voteMapper.sumAllScore(CAMPAIGN_ID)).thenReturn(new BigDecimal("79.96"));

        CampaignVote mineBtc = vote(1L, BTC, CampaignVote.UP);
        CampaignVote mineGold = vote(1L, GOLD, CampaignVote.DOWN);

        List<CampaignVote> votes = new ArrayList<>(List.of(mineBtc, mineGold));
        for (long u = 2; u <= 7; u++) votes.add(vote(u, BTC, CampaignVote.UP));   // 总正确票数 8

        unsettled(DAY1, votes);
        service.settleDay(DAY1);

        List<Settled> rows = settledRows();
        assertThat(rows).extracting(Settled::id, Settled::score)
                .as("前票 2.50、末票兜 2.51")
                .contains(tuple(mineBtc.getId(), "2.50"), tuple(mineGold.getId(), "2.51"));

        BigDecimal mine = rows.stream()
                .filter(r -> r.id().equals(mineBtc.getId()) || r.id().equals(mineGold.getId()))
                .map(r -> new BigDecimal(r.score()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(mine).as("双份 = 每票 2.5050 × 2").isEqualByComparingTo("5.01");

        assertThat(rows).filteredOn(r -> !r.id().equals(mineBtc.getId()) && !r.id().equals(mineGold.getId()))
                .as("单份的每人 2.50").hasSize(6)
                .allSatisfy(r -> assertThat(r.score()).isEqualTo("2.50"));
    }

    // ==================== 可分池的反推 ====================

    /**
     * 池子靠 {@code 100 × 已过天数 − 全场已发出的分} 反推，不存"顺延余额"。
     * <p>
     * 同样 40 张赢票：活动首日池 100 → 每票 2.50；次日若首日一分没发出去（sumAllScore 仍是 0），
     * 池就是 200 → 每票 5.00。这个"翻倍"就是顺延，它不是某一列存下来的，是减出来的。
     */
    @Test
    void 前一日全额顺延后次日池子变大() {
        upDay(BTC);
        downDay(GOLD);

        List<CampaignVote> day1 = new ArrayList<>();
        List<CampaignVote> day2 = new ArrayList<>();
        for (long u = 1; u <= 40; u++) day1.add(vote(u, BTC, CampaignVote.UP));
        for (long u = 1; u <= 40; u++) day2.add(vote(u, BTC, CampaignVote.UP));
        unsettled(DAY1, day1);
        unsettled(DAY2, day2);

        service.settleDay(DAY1);
        assertThat(settledRows()).as("首日：池 100 ÷ 40 票").hasSize(40)
                .allSatisfy(r -> assertThat(r.score()).isEqualTo("2.50"));

        service.settleDay(DAY2);
        List<Settled> both = settledRows();
        assertThat(both).hasSize(80);
        assertThat(both.subList(40, 80)).as("次日：池 200 ÷ 40 票，首日那 100 顺延了过来")
                .allSatisfy(r -> assertThat(r.score()).isEqualTo("5.00"));
    }

    // ==================== 取价的边界 ====================

    /**
     * 要的是"该 UTC 日收盘那一刻"之前的日线，endTime 得是这一日的最后一毫秒。
     * <p>
     * 【差一天就全错】传成次日 0 点整的话，Binance 会把次日那根刚开的日线也带回来，
     * 最后一根就成了"今天"而不是"要结的那天"，整日输赢集体错位。
     */
    @Test
    void 按UTC日末毫秒取日线() {
        upDay(BTC);
        upDay(GOLD);
        unsettled(DAY1, List.of(vote(1L, BTC, CampaignVote.UP)));

        service.settleDay(DAY1);

        ArgumentCaptor<Long> endTime = ArgumentCaptor.forClass(Long.class);
        verify(binance, atLeast(1))
                .getFuturesKlinesLight(eq(BTC), eq("1d"), anyInt(), endTime.capture());
        assertThat(endTime.getValue())
                .isEqualTo(DAY2.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() - 1);
    }

    // ==================== 空转 ====================

    /** 没有活动：票都不查，行情更不该取（白白挨一次 451） */
    @Test
    void 没有活动时不查票也不取行情() {
        when(campaignMapper.selectRunning()).thenReturn(null);

        service.settleDay(DAY1);

        verify(voteMapper, never()).listUnsettled(any(), any());
        verify(binance, never()).getFuturesKlinesLight(any(), any(), anyInt(), anyLong());
    }

    /** 当日没有待结算票（含重跑已结算完的一天）：不取行情、不写库 */
    @Test
    void 当日无待结算票时不取行情也不写库() {
        unsettled(DAY1, List.of());

        service.settleDay(DAY1);

        verify(binance, never()).getFuturesKlinesLight(any(), any(), anyInt(), anyLong());
        verify(voteMapper, never()).settle(anyLong(), any(), any());
    }

    // ==================== 总分汇总 ====================

    /**
     * sumScoreByUser 返回的是 JDBC 原始列标签（PG 全小写），这里按 user_id / total 取值并转成 Map。
     * <p>
     * 【未结算的票会带出一行 total=0】SQL 没筛 result IS NOT NULL，只投过票的人也在结果里。
     * 总分是对的，但别拿这个 Map 的 keySet 当"拿过分的人"用。
     */
    @Test
    void 总分按列名汇总成用户到分数的映射() {
        when(voteMapper.sumScoreByUser(CAMPAIGN_ID)).thenReturn(List.of(
                Map.of("user_id", 11L, "total", new BigDecimal("12.34")),
                Map.of("user_id", 22L, "total", new BigDecimal("0.00"))));

        assertThat(service.voteScoreByUser(CAMPAIGN_ID))
                .containsOnlyKeys(11L, 22L)
                .containsEntry(11L, new BigDecimal("12.34"));
    }

    // ==================== 手搓行 ====================

    /** 捕获到的一次 settle 调用。score 用字符串比，顺带把小数位一起钉住（列是 NUMERIC(8,2)） */
    private record Settled(Long id, String result, String score) {
    }

    private List<Settled> settledRows() {
        ArgumentCaptor<Long> id = ArgumentCaptor.forClass(Long.class);
        ArgumentCaptor<String> result = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<BigDecimal> score = ArgumentCaptor.forClass(BigDecimal.class);
        verify(voteMapper, atLeast(0)).settle(id.capture(), result.capture(), score.capture());

        List<Settled> out = new ArrayList<>();
        for (int i = 0; i < id.getAllValues().size(); i++) {
            out.add(new Settled(id.getAllValues().get(i), result.getAllValues().get(i),
                    score.getAllValues().get(i).toPlainString()));
        }
        return out;
    }

    private void unsettled(LocalDate day, List<CampaignVote> votes) {
        when(voteMapper.listUnsettled(CAMPAIGN_ID, day)).thenReturn(votes);
    }

    private CampaignVote vote(long userId, String symbol, String direction) {
        CampaignVote v = new CampaignVote();
        v.setId(nextVoteId++);
        v.setCampaignId(CAMPAIGN_ID);
        v.setUserId(userId);
        v.setSymbol(symbol);
        v.setDirection(direction);
        return v;
    }

    private void upDay(String symbol) {
        stubKlines(symbol, "100", "110");
    }

    private void downDay(String symbol) {
        stubKlines(symbol, "100", "90");
    }

    private void flatDay(String symbol) {
        stubKlines(symbol, "100", "100");
    }

    /** 首行是根用不着的旧日线：实现必须取<b>最后两根</b>，取头两根的话涨跌就反了 */
    private void stubKlines(String symbol, String prevClose, String close) {
        when(binance.getFuturesKlinesLight(eq(symbol), eq("1d"), anyInt(), anyLong()))
                .thenReturn(klines("999", prevClose, close));
    }

    /**
     * 仿 {@code getFuturesKlinesLight} 的返回：每行 8 个元素、收盘价在下标 4。
     * open/high/low 填成与 close 无关的常量 —— 实现读错下标的话，各行就一模一样，涨跌塌成平盘。
     */
    private static String klines(String... closes) {
        JSONArray rows = new JSONArray();
        for (int i = 0; i < closes.length; i++) {
            JSONArray r = new JSONArray();
            r.add(1_000L * i);        // 0 openTime
            r.add("1");               // 1 open
            r.add("99999");           // 2 high
            r.add("0.01");            // 3 low
            r.add(closes[i]);         // 4 close ← 唯一被读的位
            r.add("0");               // 5 volume
            r.add(1_000L * i + 999);  // 6 closeTime
            r.add("0");               // 7 quoteVolume
            rows.add(r);
        }
        return rows.toJSONString();
    }
}
