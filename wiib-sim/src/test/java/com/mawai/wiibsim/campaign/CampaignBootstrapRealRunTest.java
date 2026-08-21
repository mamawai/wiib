package com.mawai.wiibsim.campaign;

import com.mawai.wiibcommon.exception.BizException;
import com.mawai.wiibsim.campaign.entity.Campaign;
import com.mawai.wiibsim.campaign.mapper.CampaignMapper;
import com.mawai.wiibsim.campaign.service.CampaignService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 活动模块地基验收（非单测）：起完整 Spring 上下文、真连本地 PG。
 * <p>
 * 这个类唯一要证的事：<b>包内 {@code @MapperScan} 真的生效</b>——主类的 @MapperScan 扫不到
 * {@code wiibsim.campaign.mapper}，{@code @Autowired CampaignMapper} 注得进来本身就是结论。
 * 不 mock：被测的正是"Spring 有没有注册这个 bean"和"种子行/驼峰映射在真库上对不对"。
 * <p>
 * 跑法（项目根）：
 * <pre>
 * set -a &amp;&amp; source .env.local &amp;&amp; set +a
 * WIIB_REAL_RUN=1 mvn -o test -pl wiib-sim -am -DskipTests=false \
 *   -Dtest=CampaignBootstrapRealRunTest -Dsurefire.failIfNoSpecifiedTests=false \
 *   -DREDIS_PASSWORD=
 * </pre>
 * 末尾空的 {@code -DREDIS_PASSWORD=} 同 FuturesPositionIndexRealRunTest，理由见那个类的注释。
 * <p>
 * 前置：{@code sql/campaign.sql} 已落库（幂等，可重跑）。本类<b>只读不写</b>，不留任何痕迹。
 */
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "WIIB_REAL_RUN", matches = "1")
class CampaignBootstrapRealRunTest {

    /** 种子活动码。写死不引 Campaign 常量：这里要钉的就是"库里那行的 code 真是这个字面量" */
    private static final String SEED_CODE = "FIVEDIM_202608";

    /**
     * ★ 注入成功 = 包内 @MapperScan 生效 ★ —— 见类注释，这是本类存在的全部理由。
     */
    @Autowired
    private CampaignMapper campaignMapper;

    @Autowired
    private CampaignService campaignService;

    /** 直查 mapper：证 bean 在、SQL 能发出去、种子行映射得回来 */
    @Test
    void 包内MapperScan生效且能查到种子活动() {
        Campaign c = campaignMapper.selectActive();

        assertThat(c).as("库里应有一行 RUNNING 活动，先跑 sql/campaign.sql").isNotNull();
        assertThat(c.getCode()).isEqualTo(SEED_CODE);
        assertThat(c.getStatus()).isEqualTo(Campaign.STATUS_RUNNING);

        // 用 comparingTo 而非 isEqualTo：列是 NUMERIC(18,4)，取回来是 500.0000，
        // BigDecimal.equals 连标度一起比，500.0000 ≠ 500 会假红
        assertThat(c.getPrizePool()).isEqualByComparingTo(new BigDecimal("500"));

        // 这两个字段是下划线转驼峰的照妖镜：自动映射哪天被关掉，start_at→startAt 静默变 null，
        // 而后续所有任务（签到/投票/结算）都从这个活动窗口取边界，null 会一路带到线上
        assertThat(c.getStartAt()).as("startAt 为 null 通常是下划线转驼峰没生效").isNotNull();
        assertThat(c.getEndAt()).as("endAt 为 null 通常是下划线转驼峰没生效").isNotNull();
        assertThat(c.getEndAt()).isAfter(c.getStartAt());

        assertThat(c.getId()).isNotNull();
    }

    /**
     * Service 层薄封装，但它是后续所有任务拿活动窗口的入口，钉一下它和 mapper 取到的是同一行。
     * <p>
     * 按窗口分叉断言：requireRunning 判时间窗，排期外的日子跑本类它就该抛——
     * 直接断言"不抛"会让本类在开赛前后必红，而那恰恰是正确行为。
     */
    @Test
    void current不判窗口而requireRunning按排期放行或拦截() {
        Campaign viaService = campaignService.current();

        // current 是读路径：不管排期到没到，活动页都得能拿到这行来展示
        assertThat(viaService).isNotNull();
        assertThat(viaService.getCode()).isEqualTo(SEED_CODE);

        LocalDateTime now = LocalDateTime.now();
        boolean inWindow = !now.isBefore(viaService.getStartAt()) && now.isBefore(viaService.getEndAt());
        if (inWindow) {
            assertThat(campaignService.requireRunning().getId()).isEqualTo(viaService.getId());
        } else {
            assertThatThrownBy(campaignService::requireRunning)
                    .as("排期外 requireRunning 必须挡住所有写操作")
                    .isInstanceOf(BizException.class);
        }
    }
}
