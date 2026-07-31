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
 * 【这个类唯一要证的事】<b>包内 {@code @MapperScan} 真的生效</b>。整套活动方案押在
 * "活动结束整包 rm、业务代码一行不动"上，而这个承诺成立的前提就是
 * {@link CampaignConfig} 那句 {@code @MapperScan("com.mawai.wiibsim.campaign.mapper")}
 * 能把活动自己的 mapper 注册进来——主类 WiibSimApplication 的 {@code @MapperScan} 只覆盖
 * {@code com.mawai.wiibsim.mapper} 与 {@code wiibcommon.mapper}，<b>扫不到</b>
 * {@code wiibsim.campaign.mapper}（不是它的子包）。所以下面 {@code @Autowired CampaignMapper}
 * 能注进来这件事本身，就是结论；注不进来会直接 NoSuchBeanDefinitionException，本类红。
 * <p>
 * 其实上下文起得来就已经证完了：CampaignController 构造依赖 CampaignService、
 * CampaignService 构造依赖 CampaignMapper，链条断一环 context 就起不来。
 * 显式注入只是把这个隐式结论摆到台面上，顺带给断言一个抓手。
 * <p>
 * 【为什么不 mock】mock 掉 mapper 等于把被测的那件事（Spring 到底有没有注册这个 bean）
 * 一起 mock 掉，测试照绿而线上启动即炸。同理也不 mock DataSource：种子行是不是真在库里、
 * 下划线转驼峰有没有把 start_at→startAt 接上，只有真发给 PG 才知道。
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
     * 【为什么要按窗口分叉】requireRunning 现在除了 status 还判时间窗（半开 [startAt, endAt)，
     * Task 5 补的，理由见 CampaignService）。种子活动的排期是 2026-08-03 ~ 2026-08-17，
     * 而 status 早就是 RUNNING —— 排期外的日子跑本类，requireRunning 就该抛。
     * 直接断言"不抛"会让这个类在开赛前后必红，而那恰恰是正确行为。
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
