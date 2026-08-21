package com.mawai.wiibsim.campaign;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.mawai.wiibsim.campaign.entity.Campaign;
import com.mawai.wiibsim.campaign.entity.CampaignVote;
import com.mawai.wiibsim.campaign.mapper.CampaignVoteMapper;
import com.mawai.wiibsim.campaign.model.VoteBoard;
import com.mawai.wiibsim.campaign.service.CampaignService;
import com.mawai.wiibsim.campaign.service.CampaignVoteService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DuplicateKeyException;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

/**
 * 多空投票的真跑验收（非单测）：起完整 Spring 上下文、真连本地 PG、<b>真写 campaign_vote</b>。
 * <p>
 * 这个类要证的两件事：
 * <ol>
 *   <li>"多空二选一"的执行者是 uk_campaign_vote 唯一索引，而
 *       {@link CampaignVoteService#vote} 是靠 catch {@link DuplicateKeyException} 接住它的。
 *       这条链上有两处只有真库才验得了的环节：DDL 里那条唯一约束真的建上了
 *       （漏建的话重复投票静默插两条，一个人多空全押、稳赚投票分）；
 *       以及 PG 的 SQLState 23505 真的被翻译成了 Spring 的 DuplicateKeyException
 *       （翻译器没装或翻成别的类型，那个 catch 就是死代码，用户看到 500）。
 *       同时还要证约束卡的是"同一标的"而不是"同一天"—— 同日投另一个标的必须放行，
 *       否则唯一键列写少一列的错误 DDL 也能让前半条绿。</li>
 *   <li>看板那两条注解 SQL 真发得出去、结果拼得对。尤其
 *       {@code countByDirection} 返回的是 {@code Map<String,Object>}，
 *       key 是 JDBC 给的原始列标签 —— 实现里按 "direction"/"cnt" 取值，
 *       猜错大小写就是 NPE，而 mock 测里 key 是自己造的，永远猜得对。</li>
 * </ol>
 * <p>
 * 直接调 mapper 插不走 vote()（同 {@link CampaignCheckinRealRunTest}）：
 * 排期外的日子 vote 会先被时间窗闸门挡住，走不到唯一索引那一步。
 * 本类只钉"只有真库才证得了"的那一半（约束在、异常翻译对、注解 SQL 与列名对），
 * 另一半由 {@link com.mawai.wiibsim.campaign.service.CampaignVoteServiceTest} 用 mock 钉。
 * <p>
 * 跑法（项目根）：
 * <pre>
 * set -a &amp;&amp; source .env.local &amp;&amp; set +a
 * WIIB_REAL_RUN=1 mvn -o test -pl wiib-sim -am -DskipTests=false \
 *   -Dtest=CampaignVoteRealRunTest -Dsurefire.failIfNoSpecifiedTests=false
 * </pre>
 * <p>
 * 前置：{@code sql/campaign.sql} 已落库且库里有一场 RUNNING 活动。
 * <p>
 * 写真库的自律：user_id 一律取负数（绝不与自增正数的真人撞车），
 * 并在 {@link #清掉本次写进库的投票行()} 里按这个 id 删干净。
 */
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "WIIB_REAL_RUN", matches = "1")
class CampaignVoteRealRunTest {

    @Autowired
    private CampaignVoteMapper voteMapper;

    @Autowired
    private CampaignVoteService voteService;

    @Autowired
    private CampaignService campaignService;

    /** 合成用户 id。JUnit 每个用例新建实例，所以每条用例各拿一个，互不干扰 */
    private final long userId = -System.nanoTime();

    @AfterEach
    void 清掉本次写进库的投票行() {
        voteMapper.delete(new LambdaQueryWrapper<>(CampaignVote.class)
                .eq(CampaignVote::getUserId, userId));
    }

    /**
     * 数据库这一层：同一个 (campaign_id, user_id, vote_date, symbol) 插第二次必须被顶回来，
     * 且顶回来的异常就是 catch 语句写的那个类型；换个标的同一天则必须放行。
     */
    @Test
    void 同日同标的重复投票被唯一索引挡住换标的则放行() {
        Long campaignId = currentCampaignId();
        // 用 votingDate()（明天）而不是 utcToday()：与真实链路落进同一个日期桶，
        // 约束卡的是哪一列跟具体日期无关，但同桶更贴近线上的样子
        LocalDate today = CampaignVoteService.votingDate();

        assertThat(voteMapper.insert(row(campaignId, today, CampaignVote.SYMBOL_BTC, CampaignVote.UP)))
                .isEqualTo(1);

        assertThatThrownBy(() -> voteMapper.insert(
                row(campaignId, today, CampaignVote.SYMBOL_BTC, CampaignVote.DOWN)))
                .as("uk_campaign_vote 没建上，或异常没被翻译成 Spring 的 DuplicateKeyException")
                .isInstanceOf(DuplicateKeyException.class);

        assertThat(voteMapper.insert(row(campaignId, today, CampaignVote.SYMBOL_GOLD, CampaignVote.DOWN)))
                .as("唯一键卡的是'同一标的'，同一天投另一个标的必须能插进去")
                .isEqualTo(1);

        // 被顶回来的那一票没有偷偷插进去：库里就是 BTC/UP 与 GOLD/DOWN 两行
        assertThat(voteMapper.listMine(campaignId, userId, today))
                .extracting(CampaignVote::getSymbol, CampaignVote::getDirection)
                .containsExactlyInAnyOrder(
                        tuple(CampaignVote.SYMBOL_BTC, CampaignVote.UP),
                        tuple(CampaignVote.SYMBOL_GOLD, CampaignVote.DOWN));
    }

    /**
     * 看板走真 SQL：票数按增量比（真库里可能已有别人的票，比绝对值会被别人的数据带偏），
     * myDirection 则是我自己的行，可以比死。
     * <p>
     * 顺带把 countByDirection 返回的 Map 的 key 单独断言一次 —— 猜错列名的表现是 NPE，
     * 直接断言 key 才能一眼看出是"列标签不是这个"而不是"没查到数据"。
     */
    @Test
    void 看板按真SQL统计票数并带上我的票() {
        Long campaignId = currentCampaignId();
        // ★ 必须是 votingDate()（明天）：看板读的就是这一天。塞 utcToday() 的话
        // 插进去的行落在另一个日期桶里，board 一条都捞不到，增量断言全成 0
        LocalDate today = CampaignVoteService.votingDate();

        VoteBoard btcBefore = pick(voteService.board(userId), CampaignVote.SYMBOL_BTC);
        VoteBoard goldBefore = pick(voteService.board(userId), CampaignVote.SYMBOL_GOLD);

        voteMapper.insert(row(campaignId, today, CampaignVote.SYMBOL_BTC, CampaignVote.UP));
        voteMapper.insert(row(campaignId, today, CampaignVote.SYMBOL_GOLD, CampaignVote.DOWN));

        List<Map<String, Object>> rows =
                voteMapper.countByDirection(campaignId, today, CampaignVote.SYMBOL_BTC);
        assertThat(rows).isNotEmpty();
        assertThat(rows.getFirst())
                .as("board 里按这两个 key 取值，列标签对不上就是 NPE")
                .containsKeys("direction", "cnt");

        List<VoteBoard> board = voteService.board(userId);
        assertThat(board).extracting(VoteBoard::symbol)
                .containsExactly(CampaignVote.SYMBOL_BTC, CampaignVote.SYMBOL_GOLD);

        VoteBoard btc = pick(board, CampaignVote.SYMBOL_BTC);
        assertThat(btc.upCount() - btcBefore.upCount()).as("我这一票要落在 BTC 的 UP 上").isEqualTo(1);
        assertThat(btc.downCount() - btcBefore.downCount()).isZero();
        assertThat(btc.myDirection()).isEqualTo(CampaignVote.UP);

        VoteBoard gold = pick(board, CampaignVote.SYMBOL_GOLD);
        assertThat(gold.downCount() - goldBefore.downCount()).as("黄金这一票落在 DOWN 上").isEqualTo(1);
        assertThat(gold.upCount() - goldBefore.upCount()).isZero();
        assertThat(gold.myDirection()).isEqualTo(CampaignVote.DOWN);
    }

    // ---- 手搓行 ----

    /** 用 current() 不用 requireRunning()：本类不关心排期到没到，只要一个真实的 campaign_id */
    private Long currentCampaignId() {
        Campaign c = campaignService.current();
        assertThat(c).as("库里应有一行 RUNNING 活动，先跑 sql/campaign.sql").isNotNull();
        return c.getId();
    }

    private static VoteBoard pick(List<VoteBoard> board, String symbol) {
        return board.stream().filter(b -> b.symbol().equals(symbol)).findFirst()
                .orElseThrow(() -> new AssertionError("看板里没有 " + symbol + " 这条：" + board));
    }

    private CampaignVote row(Long campaignId, LocalDate date, String symbol, String direction) {
        CampaignVote v = new CampaignVote();
        v.setCampaignId(campaignId);
        v.setUserId(userId);
        v.setVoteDate(date);
        v.setSymbol(symbol);
        v.setDirection(direction);
        return v;
    }
}
