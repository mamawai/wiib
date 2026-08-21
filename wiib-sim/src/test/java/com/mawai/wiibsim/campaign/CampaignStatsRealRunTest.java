package com.mawai.wiibsim.campaign;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.mawai.wiibsim.campaign.mapper.CampaignStatsMapper;
import com.mawai.wiibsim.campaign.model.ClosedPositionRow;
import com.mawai.wiibsim.campaign.score.ScoreRules;
import com.mawai.wiibsim.campaign.model.EligibleUserRow;
import com.mawai.wiibsim.dto.PositionHistoryDTO;
import com.mawai.wiibsim.mapper.FuturesPositionMapper;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 活动只读聚合的真跑验收。跑在真库上，不 mock。
 * <p>
 * 最要紧的断言：listClosedPositions 的 ROI 必须与 selectPositionHistory（仓位历史页那个数）
 * 逐仓位相等——两套 SQL 各写各的，对得上才说明口径真的抄对了。
 * <p>
 * 跑法（项目根）：
 * <pre>
 * set -a &amp;&amp; source .env.local &amp;&amp; set +a
 * WIIB_REAL_RUN=1 mvn -o test -pl wiib-sim -am -DskipTests=false \
 *   -Dtest=CampaignStatsRealRunTest -Dsurefire.failIfNoSpecifiedTests=false
 * </pre>
 * <p>
 * 本测试只读不写，不造数据也不清数据 —— 真库里已有的成交就是最好的样本。
 * <p>
 * 库里没数据时用 Assumptions 不用 return：assumeTrue 报"跳过"，静默 return 是假"通过"。
 */
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "WIIB_REAL_RUN", matches = "1")
class CampaignStatsRealRunTest {

    /** 取够宽的窗口，把库里所有历史数据都罩进来 */
    private static final LocalDateTime FROM = LocalDateTime.of(2000, 1, 1, 0, 0);
    private static final LocalDateTime TO = LocalDateTime.of(2099, 1, 1, 0, 0);

    @Autowired
    private CampaignStatsMapper statsMapper;

    @Autowired
    private FuturesPositionMapper positionMapper;

    @Test
    void 仓位ROI必须与仓位历史页显示的一致() {
        List<ClosedPositionRow> rows = statsMapper.listClosedPositions(FROM, TO);
        Assumptions.assumeFalse(rows.isEmpty(), "库里还没有已平仓位，无从比对");

        // 挑持仓最多的那个用户比对，样本最厚
        Long userId = rows.stream()
                .collect(Collectors.groupingBy(ClosedPositionRow::getUserId, Collectors.counting()))
                .entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .orElseThrow().getKey();

        Page<PositionHistoryDTO> page = new Page<>(1, 200);
        List<PositionHistoryDTO> expected = positionMapper.selectPositionHistory(page, userId, null).getRecords();
        Assumptions.assumeFalse(expected.isEmpty(), "该用户在仓位历史页没有记录");

        Map<Long, ClosedPositionRow> mine = rows.stream()
                .filter(r -> r.getUserId().equals(userId))
                .collect(Collectors.toMap(ClosedPositionRow::getPositionId, Function.identity()));

        int compared = 0;
        for (PositionHistoryDTO exp : expected) {
            ClosedPositionRow got = mine.get(exp.getId());
            if (exp.getRoiPct() == null) continue;   // invested_margin=0，两边都不给 ROI
            compared++;

            assertThat(got)
                    .as("仓位 %s 在仓位历史页有，活动聚合里却没有", exp.getId())
                    .isNotNull();
            assertThat(got.getInvestedMargin())
                    .as("仓位 %s 的累计投入保证金对不上", exp.getId())
                    .isEqualByComparingTo(exp.getInvestedMargin());
            assertThat(got.getNetPnl())
                    .as("仓位 %s 的已实现净盈亏对不上", exp.getId())
                    .isEqualByComparingTo(exp.getRealizedPnl());
            // 页面上是百分数保留两位，这里换算过去比
            assertThat(got.roi().multiply(new BigDecimal("100")).setScale(2, RoundingMode.HALF_UP))
                    .as("仓位 %s 的 ROI 对不上", exp.getId())
                    .isEqualByComparingTo(exp.getRoiPct());
        }

        // 上面那个 continue 是条静默通路：万一全员 roiPct=null，循环一圈一个断言没跑，用例照绿。
        // 那正是类注释里说的"骗人"。钉死至少真比过一笔，绿灯才有含义。
        assertThat(compared).as("一笔都没比到，这次绿灯什么也没证明").isPositive();
    }

    /**
     * 非真人号必须被挡在名单外，否则真人第一天就被碾平 ——
     * 真库里 AI_TRADER 一个号占了 37 个已平仓位里的 32 个。
     */
    @Test
    void 只有纯数字LinuxDoID才进参与名单() {
        List<EligibleUserRow> users = statsMapper.listEligibleUsers();

        assertThat(users).isNotEmpty();
        assertThat(users).allSatisfy(u ->
                assertThat(u.getLinuxDoId())
                        .as("参与名单里混进了非数字 linux_do_id 的账号（机器人/管理员/邀请码用户）")
                        .matches("\\d+"));
        // 逐个点名真库里已知的四类非真人号，确保正则不是碰巧过的
        assertThat(users).extracting(EligibleUserRow::getLinuxDoId)
                .doesNotContain("AI_TRADER", "local-admin")
                .noneMatch(id -> id.startsWith("internal:"));
    }

    /** 八条 SQL 全都得能真发出去：注解 SQL 写错了只有跑起来才知道 */
    @Test
    void 全部聚合查询都能执行() {
        assertThat(statsMapper.listClosedPositions(FROM, TO)).isNotNull();
        assertThat(statsMapper.listSpotOrders(FROM, TO, new BigDecimal("1000"))).isNotNull();
        assertThat(statsMapper.countPredictionHits(FROM, TO, ScoreRules.PREDICTION_MIN_COST)).isNotNull();
        assertThat(statsMapper.countStopLossTriggered(FROM, TO)).isNotNull();
        assertThat(statsMapper.countIsolatedLiquidations(FROM, TO)).isNotNull();
        assertThat(statsMapper.countCrossLiquidations(FROM, TO)).isNotNull();
        assertThat(statsMapper.listCommenters(FROM, TO)).isNotNull();
        assertThat(statsMapper.listEligibleUsers()).isNotNull();
    }
}
