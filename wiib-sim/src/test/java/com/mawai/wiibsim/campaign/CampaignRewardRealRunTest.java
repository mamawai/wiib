package com.mawai.wiibsim.campaign;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.mawai.wiibsim.campaign.entity.Campaign;
import com.mawai.wiibsim.campaign.entity.CampaignReward;
import com.mawai.wiibsim.campaign.entity.CampaignVote;
import com.mawai.wiibsim.campaign.mapper.CampaignMapper;
import com.mawai.wiibsim.campaign.mapper.CampaignRewardMapper;
import com.mawai.wiibsim.campaign.mapper.CampaignStatsMapper;
import com.mawai.wiibsim.campaign.mapper.CampaignVoteMapper;
import com.mawai.wiibsim.campaign.service.CampaignSettleService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 结算与领取那八条注解 SQL 的真跑验收（非单测）：起完整 Spring 上下文、真连本地 PG、
 * <b>真写 campaign / campaign_reward / campaign_vote</b>（全是合成行，跑完删干净）。
 * <p>
 * 【这个类要证什么】这八条 SQL 全是本任务新加的，加完一次都没真发出去过，而它们守着的是真金白银。
 * 静态检查最多排掉列名拼错，下面这几件只有真库才见分晓：
 * <ol>
 *   <li><b>{@code selectActive} 的 {@code status IN ('RUNNING','SETTLING')}</b> —— 少收 SETTLING
 *       的话，活动一结算就查不到，所有人的领取入口静默消失；多收 DONE 的话，收摊的老活动会诈尸。</li>
 *   <li><b>campaign_reward 十三列的实体映射</b> —— 尤其 {@code created_at → createdAt}：
 *       领取要拿它算领取期限（{@code getCreatedAt().plusDays(...)}），映射不上就是 NPE，
 *       而 mock 测里这个字段是自己 set 进去的，永远不为 null。</li>
 *   <li><b>casClaim / markSuccess / markFailed 三条 CAS 的返回值</b> —— 整套防重复领取就架在
 *       "命中 0 行返 0"上。这三条的 WHERE 各自收哪些状态（casClaim 收 PENDING+FAILED，
 *       另两条只收 CLAIMED）是并发下唯一的锁，写错了双击就能发两次。</li>
 *   <li><b>{@code listUnsettledDates} 的 {@code LocalDate} ↔ PG {@code DATE}</b> —— 结算前那道
 *       "投票结完没"的闸全靠它。类型绑不上要么抛异常（还好，响的），要么恒空（结算照跑，
 *       最后一天的投票分静默按 0 计，钱按少算的权重发出去）。</li>
 *   <li><b>{@code selectLinuxDoId} 里那个带引号的 {@code "user"} 表名</b> —— user 是 SQL 保留字，
 *       引号掉了就整条报错，而它是领取时唯一的身份核对。</li>
 * </ol>
 * <p>
 * 【为什么不整段跑 settle() 而是直接调 mapper】同 {@link CampaignVoteSettleRealRunTest} 的分工，
 * 但理由更硬：{@code settle()} 会给<b>真库里的真实用户</b>按当下积分生成奖励行、并把种子活动
 * 不可逆地翻成 SETTLING。那是所有者的开发库和所有者的钱，测试不该碰。
 * 分配算法（总额恰好等于奖池、0.00 不落行、拒绝结算的几种情形）由
 * {@link com.mawai.wiibsim.campaign.service.CampaignSettleServiceTest} 用 mock 钉，
 * 本类只钉"只有真库才证得了"的那一半。
 * <p>
 * 跑法（项目根）：
 * <pre>
 * set -a &amp;&amp; source .env.local &amp;&amp; set +a
 * WIIB_REAL_RUN=1 mvn -o test -pl wiib-sim -am -DskipTests=false \
 *   -Dtest=CampaignRewardRealRunTest -Dsurefire.failIfNoSpecifiedTests=false
 * </pre>
 * <p>
 * 【写真库的自律】活动码带 {@code __TEST__} 前缀且 user_id 一律取<b>负数</b>（真 user.id 是自增正数），
 * 投票日取 2020 年（活动 2026 年才开赛），奖励行全挂在合成活动名下 ——
 * 于是每一处断言都能比死而不是比增量。跑完在 {@link #清掉本次写进库的合成行()} 里按
 * campaign_id / user_id 整批删除。
 */
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "WIIB_REAL_RUN", matches = "1")
class CampaignRewardRealRunTest {

    /** 合成活动码。带前缀是为了万一残留也一眼认得出、删得掉 */
    private static final String TEST_CODE = "__TEST__REWARD_" + System.nanoTime();

    /** 活动 2026-08 才开赛，这两天真库里不可能有别人的票 */
    private static final LocalDate DAY_A = LocalDate.of(2020, 1, 1);
    private static final LocalDate DAY_B = LocalDate.of(2020, 1, 2);

    @Autowired
    private CampaignMapper campaignMapper;

    @Autowired
    private CampaignRewardMapper rewardMapper;

    @Autowired
    private CampaignVoteMapper voteMapper;

    @Autowired
    private CampaignStatsMapper statsMapper;

    /** 合成用户 id，负数保证绝不与真人撞车 */
    private final long user = -System.nanoTime();

    /** 合成活动的主键，由 @AfterEach 用来整批删除 */
    private Long testCampaignId;

    @AfterEach
    void 清掉本次写进库的合成行() {
        if (testCampaignId != null) {
            rewardMapper.delete(new LambdaQueryWrapper<>(CampaignReward.class)
                    .eq(CampaignReward::getCampaignId, testCampaignId));
            voteMapper.delete(new LambdaQueryWrapper<>(CampaignVote.class)
                    .eq(CampaignVote::getCampaignId, testCampaignId));
            campaignMapper.deleteById(testCampaignId);
        }
    }

    /**
     * ★ selectActive 三种状态各判一次 ★
     * <p>
     * 合成活动的 start_at 取 2099 年，好让它稳稳压过种子活动赢下
     * {@code ORDER BY start_at DESC LIMIT 1} —— 否则 SETTLING 到底收不收，从返回值上看不出来。
     * <p>
     * 【RUNNING → SETTLING → DONE 顺着走一遍】这三步正是活动真实的一生：
     * SETTLING 必须还查得到（结算后活动页要显示榜单与领取入口），
     * DONE 必须查不到（收摊了，入口就该消失，此时应当露出种子那场）。
     */
    @Test
    void selectActive收RUNNING与SETTLING但不收DONE() {
        Long seedId = campaignMapper.selectActive().getId();
        assertThat(seedId).as("库里应有一行 RUNNING 活动，先跑 sql/campaign.sql").isNotNull();

        insertTestCampaign(Campaign.STATUS_RUNNING);

        Campaign got = campaignMapper.selectActive();
        assertThat(got.getId()).as("2099 年开赛，该压过种子那场").isEqualTo(testCampaignId);
        assertThat(got.getCode()).isEqualTo(TEST_CODE);
        assertThat(got.getPrizePool()).as("NUMERIC(18,4) 取回来带标度，用 comparingTo")
                .isEqualByComparingTo("500");

        flipStatus(Campaign.STATUS_SETTLING);
        assertThat(campaignMapper.selectActive().getId())
                .as("SETTLING 不收的话，活动一结算领取入口就静默消失了")
                .isEqualTo(testCampaignId);

        flipStatus(Campaign.STATUS_DONE);
        assertThat(campaignMapper.selectActive().getId())
                .as("DONE 收摊了就不该再出现，应当露出种子那场")
                .isEqualTo(seedId);
    }

    /**
     * ★ 奖励行的十三列映射 + 三条 CAS 的状态机 ★
     * <p>
     * 状态机走的是真实的两条路：
     * PENDING →(casClaim) CLAIMED →(markSuccess) SUCCESS，以及失败重来那条
     * CLAIMED →(markFailed) FAILED →(casClaim) CLAIMED。
     * 每一步都顺带验一次"重复调必须返 0"—— 那个 0 是整套防重复领取的全部依仗。
     */
    @Test
    void 奖励行落得进库且三条CAS各自只认该认的状态() {
        insertTestCampaign(Campaign.STATUS_SETTLING);

        assertThat(rewardMapper.countByCampaign(testCampaignId))
                .as("合成活动名下本来一行都没有").isZero();

        String outTradeNo = CampaignSettleService.outTradeNo(TEST_CODE, user);
        insertReward(outTradeNo);

        assertThat(rewardMapper.countByCampaign(testCampaignId)).isEqualTo(1);

        // ---- 十三列映射：结算写进去的每个字段都取得回来 ----
        CampaignReward mine = rewardMapper.selectMine(testCampaignId, user);
        assertThat(mine).as("selectMine 按 (campaign_id, user_id) 捞得到").isNotNull();
        assertThat(mine.getStatus()).isEqualTo(CampaignReward.PENDING);
        assertThat(mine.getOutTradeNo()).isEqualTo(outTradeNo);
        assertThat(mine.getLinuxDoId()).as("结算时留空占位，列是 NOT NULL 所以是空串不是 null").isEmpty();
        assertThat(mine.getLdcAmount()).isEqualByComparingTo("300.00");
        assertThat(mine.getFinalScore()).isEqualByComparingTo("3000.00");
        assertThat(mine.getVoteScore()).isEqualByComparingTo("0.99");
        assertThat(mine.getPenalty()).as("罚分是负数，别在映射里丢了符号").isEqualTo(-5);
        assertThat(mine.getCreatedAt())
                .as("★ 领取要拿它算期限（getCreatedAt().plusDays），映射不上就是 NPE ★")
                .isNotNull();

        Long id = mine.getId();

        // ---- casClaim：PENDING 能抢到，抢到之后第二个人抢不到 ----
        assertThat(rewardMapper.casClaim(id, "218272", "mawai"))
                .as("PENDING 该被 WHERE 收进来").isEqualTo(1);
        assertThat(rewardMapper.casClaim(id, "999999", "someone-else"))
                .as("★ 已经是 CLAIMED，双击的第二个请求必须拿到 0 ★").isZero();

        CampaignReward claimed = rewardMapper.selectMine(testCampaignId, user);
        assertThat(claimed.getStatus()).isEqualTo(CampaignReward.CLAIMED);
        assertThat(claimed.getLinuxDoId()).as("二次授权拿到的身份要回填").isEqualTo("218272");
        assertThat(claimed.getUsername()).isEqualTo("mawai");
        assertThat(claimed.getUpdatedAt()).as("NOW() 没写进 TIMESTAMP 列的话这里会红").isNotNull();

        // ---- markFailed：CLAIMED 收，落 FAILED 并记原文 ----
        assertThat(rewardMapper.markFailed(id, "HTTP 400 收款用户不存在")).isEqualTo(1);
        CampaignReward failed = rewardMapper.selectMine(testCampaignId, user);
        assertThat(failed.getStatus()).isEqualTo(CampaignReward.FAILED);
        assertThat(failed.getErrorMsg()).isEqualTo("HTTP 400 收款用户不存在");
        assertThat(rewardMapper.markSuccess(id, "tn-should-not-apply"))
                .as("已经是 FAILED，markSuccess 只收 CLAIMED，必须 0 行").isZero();

        // ---- FAILED 还能再领一次：修好问题后重来那条路 ----
        assertThat(rewardMapper.casClaim(id, "218272", "mawai"))
                .as("★ FAILED 必须还能进 CLAIMED，否则发放失败的人就永久卡死了 ★").isEqualTo(1);

        // ---- markSuccess：落 SUCCESS、记流水号、把上次的错误信息清掉 ----
        assertThat(rewardMapper.markSuccess(id, "87597927423505256")).isEqualTo(1);
        CampaignReward success = rewardMapper.selectMine(testCampaignId, user);
        assertThat(success.getStatus()).isEqualTo(CampaignReward.SUCCESS);
        assertThat(success.getExternalRef()).isEqualTo("87597927423505256");
        assertThat(success.getErrorMsg()).as("成功了要把上次的错误信息清掉，否则对账时看着像还在错").isNull();
        assertThat(rewardMapper.casClaim(id, "218272", "mawai"))
                .as("★ SUCCESS 绝不能再被抢进 CLAIMED，那是重复发钱的入口 ★").isZero();
    }

    /**
     * ★ listUnsettledDates：结算前那道"投票结完没"的闸 ★
     * <p>
     * 它恒返回空的话（比如 LocalDate 绑不上、或 result IS NULL 写错），结算会照跑，
     * 最后一天的投票分静默按 0 计入榜单，钱就按少算的权重发出去了 —— 一次性、不可逆、无声。
     */
    @Test
    void 未结算投票日按天去重且升序返回() {
        insertTestCampaign(Campaign.STATUS_RUNNING);

        assertThat(voteMapper.listUnsettledDates(testCampaignId))
                .as("合成活动名下本来一票都没有").isEmpty();

        // 同一天两张票：GROUP BY 要把它去重成一个日子
        insertVote(DAY_B, CampaignVote.SYMBOL_BTC);
        insertVote(DAY_A, CampaignVote.SYMBOL_BTC);
        insertVote(DAY_A, CampaignVote.SYMBOL_GOLD);

        assertThat(voteMapper.listUnsettledDates(testCampaignId))
                .as("按天去重、升序 —— 运营看的就是这个列表，顺序乱了不好读")
                .containsExactly(DAY_A, DAY_B);

        // 把 DAY_A 那两张结掉，它就该从清单里消失，DAY_B 留着
        for (CampaignVote v : voteMapper.listUnsettled(testCampaignId, DAY_A)) {
            assertThat(voteMapper.settle(v.getId(), CampaignVote.LOSE, BigDecimal.ZERO)).isEqualTo(1);
        }
        assertThat(voteMapper.listUnsettledDates(testCampaignId))
                .as("结完的日子必须掉出去，否则结算永远被自己挡住")
                .containsExactly(DAY_B);
    }

    /**
     * ★ selectLinuxDoId：{@code "user"} 是 SQL 保留字，引号掉了整条就报错 ★
     * <p>
     * 它是领取时唯一的身份核对 —— 挂了就没人领得到，而错了（比如取成别的列）就是把钱发给别人。
     */
    @Test
    void 能从user表取到本人的LinuxDo号() {
        assertThat(statsMapper.selectLinuxDoId(1L))
                .as("id=1 是平台所有者，linux_do_id 该是纯数字")
                .isNotNull()
                .matches("\\d+");

        assertThat(statsMapper.selectLinuxDoId(user))
                .as("查不到的人返回 null 而不是抛 —— 领取那侧靠这个 null 走"
                        + "「与当前登录账号不符」的拒绝路径")
                .isNull();
    }

    // ---- 手搓行 ----

    /** start_at 取 2099 年：好让它稳稳压过种子活动赢下 ORDER BY start_at DESC LIMIT 1 */
    private void insertTestCampaign(String status) {
        Campaign c = new Campaign();
        c.setCode(TEST_CODE);
        c.setName("真跑用合成活动");
        c.setStartAt(LocalDateTime.of(2099, 1, 1, 0, 0));
        c.setEndAt(LocalDateTime.of(2099, 1, 15, 0, 0));
        c.setPrizePool(new BigDecimal("500"));
        c.setStatus(status);
        assertThat(campaignMapper.insert(c)).isEqualTo(1);
        testCampaignId = c.getId();
    }

    private void flipStatus(String status) {
        Campaign c = new Campaign();
        c.setId(testCampaignId);
        c.setStatus(status);
        assertThat(campaignMapper.updateById(c)).isEqualTo(1);
    }

    /** 照 CampaignSettleService 落表那段逐字段填，好让这里验的就是它写出去的形状 */
    private void insertReward(String outTradeNo) {
        CampaignReward r = new CampaignReward();
        r.setCampaignId(testCampaignId);
        r.setUserId(user);
        r.setLinuxDoId("");
        r.setUsername("synthetic");
        r.setTradeScore(3000);
        r.setDailyScore(5);
        r.setVoteScore(new BigDecimal("0.99"));
        r.setPenalty(-5);
        r.setFinalScore(new BigDecimal("3000.00"));
        r.setLdcAmount(new BigDecimal("300.00"));
        r.setStatus(CampaignReward.PENDING);
        r.setOutTradeNo(outTradeNo);
        assertThat(rewardMapper.insert(r)).isEqualTo(1);
    }

    private void insertVote(LocalDate date, String symbol) {
        CampaignVote v = new CampaignVote();
        v.setCampaignId(testCampaignId);
        v.setUserId(user);
        v.setVoteDate(date);
        v.setSymbol(symbol);
        v.setDirection(CampaignVote.UP);
        assertThat(voteMapper.insert(v)).isEqualTo(1);
    }
}
