package com.mawai.wiibsim.campaign;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.mawai.wiibsim.campaign.entity.Campaign;
import com.mawai.wiibsim.campaign.entity.CampaignVote;
import com.mawai.wiibsim.campaign.mapper.CampaignVoteMapper;
import com.mawai.wiibsim.campaign.service.CampaignService;
import com.mawai.wiibsim.campaign.service.CampaignVoteService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 投票结算那几条注解 SQL 的真跑验收（非单测）：起完整 Spring 上下文、真连本地 PG、<b>真写
 * campaign_vote</b>。
 * <p>
 * 【这个类要证什么】{@code listUnsettled} / {@code sumScoreByUser} / {@code settle} 是上一任务
 * 加进 mapper 的，加完一次都没真发出去过；{@code sumScoreUpTo} 是本任务新加的。
 * 静态检查最多能排掉列名拼错，下面这几件只有真跑才见分晓：
 * <ol>
 *   <li>{@code LocalDate} ↔ PG {@code DATE} 的绑定 —— 类型映射不对的话 vote_date 条件永远不命中，
 *       结算天天报"无待结算票"，静默不发分；</li>
 *   <li>{@code settle} 里 {@code updated_at = NOW()} 往 {@code TIMESTAMP} 列写 —— NOW() 是
 *       timestamptz，隐式转换不成立就整条 UPDATE 抛错；</li>
 *   <li>CAS 的返回值 —— {@code WHERE result IS NULL} 命中 0 行时驱动到底返不返 0。
 *       这条是幂等的全部依仗：重跑一天不能覆盖已发的分；</li>
 *   <li>{@code sumScoreByUser} 返回的 {@code Map} 的 key 是 JDBC 原始列标签，
 *       {@code voteScoreByUser} 里那两个强转（{@code (Number)} / {@code (BigDecimal)}）
 *       猜错就是 ClassCastException，而 mock 测里 key 和类型都是自己造的，永远猜得对。</li>
 * </ol>
 * <p>
 * 【为什么不整段跑 settleDay 而是直接调 mapper】两个理由，都不是图省事：
 * <ul>
 *   <li>本机被 Binance 按地区拒（451），{@code resolveOutcome} 必然返回 null，
 *       {@code settleDay} 会按设计"整天不结算"直接返回 —— 一条 UPDATE 都发不出去，
 *       那样这个类什么也证不了。就算换台机器能连上，"昨天 BTC 涨没涨"也不该决定测试红绿。</li>
 *   <li>{@code settleDay} 结的是<b>全场</b>那一天的待结算票，连的又是所有者的真实开发库 ——
 *       真跑一次会把别人的真票也按当时的行情结掉，而 CAS 让这事没法回退。</li>
 * </ul>
 * 所以本类只钉"只有真库才证得了"的那一半（四条 SQL 真发得出去、真干它声称的活），
 * 另一半（谁赢谁输、每票分多少、拿不到价时一行都不写）由
 * {@link com.mawai.wiibsim.campaign.service.CampaignVoteSettleTest} 用 mock 钉。
 * 与 {@link CampaignVoteRealRunTest} / {@link CampaignCheckinRealRunTest} 同一分工。
 * <p>
 * 跑法（项目根）：
 * <pre>
 * set -a &amp;&amp; source .env.local &amp;&amp; set +a
 * WIIB_REAL_RUN=1 mvn -o test -pl wiib-sim -am -DskipTests=false \
 *   -Dtest=CampaignVoteSettleRealRunTest -Dsurefire.failIfNoSpecifiedTests=false
 * </pre>
 * <p>
 * 前置：{@code sql/campaign.sql} 已落库且库里有一场 RUNNING 活动。
 * <p>
 * 【写真库的自律】user_id 一律取<b>负数</b>（真 user.id 是自增正数，负号保证绝不与真人撞车），
 * vote_date 取 2020 年（活动 2026 年才开赛，那两天真库里绝无第二个人的票，
 * listUnsettled 的结果才能比死而不是比增量），跑完在 {@link #清掉本次写进库的投票行()} 里删干净。
 */
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "WIIB_REAL_RUN", matches = "1")
class CampaignVoteSettleRealRunTest {

    /** 活动 2026-08 才开赛，这几天真库里不可能有别人的票 */
    private static final LocalDate DAY_A = LocalDate.of(2020, 1, 1);
    private static final LocalDate DAY_B = LocalDate.of(2020, 1, 2);
    private static final LocalDate DAY_C = LocalDate.of(2020, 1, 3);

    @Autowired
    private CampaignVoteMapper voteMapper;

    @Autowired
    private CampaignVoteService voteService;

    @Autowired
    private CampaignService campaignService;

    /** 合成用户 id。JUnit 每个用例新建实例，所以每条用例各拿一对，互不干扰 */
    private final long winner = -System.nanoTime();
    private final long lurker = -System.nanoTime() - 1;

    @AfterEach
    void 清掉本次写进库的投票行() {
        voteMapper.delete(new LambdaQueryWrapper<>(CampaignVote.class)
                .in(CampaignVote::getUserId, List.of(winner, lurker)));
    }

    /**
     * 一条龙：捞待结算 → 回填 → 再捞捞不到 → 重复回填被 CAS 顶回来 → 两个汇总口径都对得上。
     */
    @Test
    void 四条SQL在真库上各跑一次并如实干活() {
        Long campaignId = currentCampaignId();
        BigDecimal upToABefore = voteMapper.sumScoreUpTo(campaignId, DAY_A);
        BigDecimal upToCBefore = voteMapper.sumScoreUpTo(campaignId, DAY_C);
        assertThat(upToABefore).as("COALESCE 兜着，一票没有也该是 0 而不是 null").isNotNull();

        insert(winner, DAY_A, CampaignVote.SYMBOL_BTC, CampaignVote.UP);
        insert(winner, DAY_A, CampaignVote.SYMBOL_GOLD, CampaignVote.DOWN);
        insert(winner, DAY_B, CampaignVote.SYMBOL_BTC, CampaignVote.DOWN);
        insert(winner, DAY_C, CampaignVote.SYMBOL_BTC, CampaignVote.UP);   // 这张一直不结，留着当"未结算"样本

        // ---- listUnsettled：认得 LocalDate，也真的只捞 result IS NULL ----
        List<CampaignVote> unsettled = voteMapper.listUnsettled(campaignId, DAY_A);
        assertThat(unsettled)
                .as("2020-01-01 只可能有本用例刚插的那两张")
                .hasSize(2)
                .allSatisfy(v -> {
                    assertThat(v.getUserId()).isEqualTo(winner);
                    assertThat(v.getVoteDate()).isEqualTo(DAY_A);
                    assertThat(v.getResult()).isNull();
                });

        Long btcId = pick(unsettled, CampaignVote.SYMBOL_BTC).getId();
        Long goldId = pick(unsettled, CampaignVote.SYMBOL_GOLD).getId();

        // ---- settle：NOW() 落得进 TIMESTAMP 列，结果与分数真回填 ----
        assertThat(voteMapper.settle(btcId, CampaignVote.WIN, new BigDecimal("3.25")))
                .as("命中一行未结算的票，该返回 1").isEqualTo(1);
        assertThat(voteMapper.settle(goldId, CampaignVote.LOSE, BigDecimal.ZERO)).isEqualTo(1);

        CampaignVote btc = voteMapper.selectById(btcId);
        assertThat(btc.getResult()).isEqualTo(CampaignVote.WIN);
        assertThat(btc.getScore()).isEqualByComparingTo("3.25");
        assertThat(btc.getUpdatedAt()).as("NOW() 没写进去的话这列会是 null").isNotNull();

        // ---- CAS：同一行再结一次，必须一行都不改 ----
        assertThat(voteMapper.settle(btcId, CampaignVote.LOSE, new BigDecimal("99.99")))
                .as("result 已非空，WHERE 命中 0 行，幂等全靠这个 0").isZero();
        CampaignVote again = voteMapper.selectById(btcId);
        assertThat(again.getResult()).as("重跑不许改判").isEqualTo(CampaignVote.WIN);
        assertThat(again.getScore()).as("重跑不许改分").isEqualByComparingTo("3.25");

        // 后一天那张也结掉，专门用来验 sumScoreUpTo 的日期上界真的在卡
        Long dayBId = voteMapper.listUnsettled(campaignId, DAY_B).getFirst().getId();
        assertThat(voteMapper.settle(dayBId, CampaignVote.WIN, new BigDecimal("1.00"))).isEqualTo(1);

        // ---- 结完就不该再被捞出来 ----
        assertThat(voteMapper.listUnsettled(campaignId, DAY_A))
                .as("两张都结完了，这一天该空了").isEmpty();
        assertThat(voteMapper.listUnsettled(campaignId, DAY_C))
                .as("另一天那张还没结，不能被顺手带走").hasSize(1);

        // ---- sumScoreUpTo：日期上界真的在卡，且 LOSE 的 0 与未结算的 NULL 都按 0 计 ----
        assertThat(voteMapper.sumScoreUpTo(campaignId, DAY_A).subtract(upToABefore))
                .as("问到 DAY_A 就只能加到 DAY_A：后一天那 1.00 不许混进来，"
                        + "混进来的话补结算旧日子时池子会被算小甚至夹到 0")
                .isEqualByComparingTo("3.25");
        assertThat(voteMapper.sumScoreUpTo(campaignId, DAY_C).subtract(upToCBefore))
                .as("问到 DAY_C 就该含 3.25 + 1.00；未结算那张由 COALESCE(score,0) 折成 0")
                .isEqualByComparingTo("4.25");

        // ---- sumScoreByUser：不卡日期（榜单要全场总分），列标签与强转都对得上 ----
        Map<Long, BigDecimal> byUser = voteService.voteScoreByUser(campaignId);
        assertThat(byUser.get(winner))
                .as("user_id / total 两个 key 或那两个强转对不上，这里不是 null 就是 ClassCastException")
                .isNotNull()
                .isEqualByComparingTo("4.25");
    }

    /**
     * 只投过票、一次都没结算过的人，在 sumScoreByUser 里也会带出一行 total=0。
     * <p>
     * SQL 没筛 {@code result IS NOT NULL}，{@code COALESCE(score,0)} 把未结算的票折成 0 ——
     * 总分是对的，但"出现在结果里"不等于"拿过分"。别拿这个 Map 的 keySet 当参与名单用，
     * 那样连一张票都没结的人也会被当成得过分的人。mapper 上的注释就是照这条实测写的。
     */
    @Test
    void 只有未结算票的人也在汇总里出现且为零分() {
        Long campaignId = currentCampaignId();
        insert(lurker, DAY_A, CampaignVote.SYMBOL_BTC, CampaignVote.UP);

        assertThat(voteService.voteScoreByUser(campaignId))
                .containsKey(lurker);
        assertThat(voteService.voteScoreByUser(campaignId).get(lurker))
                .isEqualByComparingTo("0");
    }

    // ---- 手搓行 ----

    /** 用 current() 不用 requireRunning()：结算本来就跑在窗口之外，本类也不关心排期到没到 */
    private Long currentCampaignId() {
        Campaign c = campaignService.current();
        assertThat(c).as("库里应有一行 RUNNING 活动，先跑 sql/campaign.sql").isNotNull();
        return c.getId();
    }

    private static CampaignVote pick(List<CampaignVote> votes, String symbol) {
        return votes.stream().filter(v -> v.getSymbol().equals(symbol)).findFirst()
                .orElseThrow(() -> new AssertionError("没捞到 " + symbol + " 那张：" + votes));
    }

    private void insert(long userId, LocalDate date, String symbol, String direction) {
        CampaignVote v = new CampaignVote();
        v.setCampaignId(currentCampaignId());
        v.setUserId(userId);
        v.setVoteDate(date);
        v.setSymbol(symbol);
        v.setDirection(direction);
        assertThat(voteMapper.insert(v)).isEqualTo(1);
    }
}
