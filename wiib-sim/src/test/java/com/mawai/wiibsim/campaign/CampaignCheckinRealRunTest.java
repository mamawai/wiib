package com.mawai.wiibsim.campaign;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.mawai.wiibsim.campaign.entity.Campaign;
import com.mawai.wiibsim.campaign.entity.CampaignCheckin;
import com.mawai.wiibsim.campaign.mapper.CampaignCheckinMapper;
import com.mawai.wiibsim.campaign.service.CampaignCheckinService;
import com.mawai.wiibsim.campaign.service.CampaignService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DuplicateKeyException;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 签到去重的真跑验收（非单测）：起完整 Spring 上下文、真连本地 PG、<b>真写 campaign_checkin</b>。
 * <p>
 * 这个类唯一要证的事：uk_campaign_checkin 唯一约束真的建上了、
 * PG 的 23505 真的被翻译成 DuplicateKeyException——两处都只有真库才验得了。
 * 直接调 mapper 插不走 checkin()：排期外的日子会先被时间窗闸门挡住，走不到唯一索引；
 * 另一半（异常 → BizException）由
 * {@link com.mawai.wiibsim.campaign.service.CampaignCheckinServiceTest} 用 mock 钉。
 * 两半合起来覆盖完整链条，且都不依赖"今天是几号"。
 * <p>
 * 跑法（项目根）：
 * <pre>
 * set -a &amp;&amp; source .env.local &amp;&amp; set +a
 * WIIB_REAL_RUN=1 mvn -o test -pl wiib-sim -am -DskipTests=false \
 *   -Dtest=CampaignCheckinRealRunTest -Dsurefire.failIfNoSpecifiedTests=false
 * </pre>
 * <p>
 * 前置：{@code sql/campaign.sql} 已落库且库里有一场 RUNNING 活动。
 * <p>
 * 写真库的自律：user_id 一律取负数（绝不与自增正数的真人撞车），
 * 并在 {@link #清掉本次写进库的签到行()} 里按这个 id 删干净。
 */
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "WIIB_REAL_RUN", matches = "1")
class CampaignCheckinRealRunTest {

    @Autowired
    private CampaignCheckinMapper checkinMapper;

    @Autowired
    private CampaignCheckinService checkinService;

    @Autowired
    private CampaignService campaignService;

    /** 合成用户 id。JUnit 每个用例新建实例，所以每条用例各拿一个，互不干扰 */
    private final long userId = -System.nanoTime();

    @AfterEach
    void 清掉本次写进库的签到行() {
        checkinMapper.delete(new LambdaQueryWrapper<>(CampaignCheckin.class)
                .eq(CampaignCheckin::getUserId, userId));
    }

    /**
     * 数据库这一层：同一个 (campaign_id, user_id, checkin_date) 插第二次必须被顶回来，
     * 且顶回来的异常就是 catch 语句写的那个类型。
     */
    @Test
    void 同日重复插入被唯一索引挡住并翻成DuplicateKeyException() {
        Long campaignId = currentCampaignId();
        LocalDate today = LocalDate.now();

        assertThat(checkinMapper.insert(row(campaignId, today))).isEqualTo(1);

        assertThatThrownBy(() -> checkinMapper.insert(row(campaignId, today)))
                .as("uk_campaign_checkin 没建上，或异常没被翻译成 Spring 的 DuplicateKeyException")
                .isInstanceOf(DuplicateKeyException.class);

        // 顶回来之后库里仍然只有一行，不是"报了错但也插进去了"
        assertThat(myRowCount(campaignId))
                .as("重复插入被拒之后，库里该只剩最早那一行")
                .isEqualTo(1);
    }

    /**
     * 换一天就能再插一行 —— 唯一索引卡的是"同一天"，不是"同一个人"。
     * 少了这条，一个把 user_id 单独做成唯一键的错误 DDL 也能让上面那条绿。
     * 顺带把 countByDate 与 listByCampaign 两条注解 SQL 都真发一次：注解 SQL 写错了只有跑起来才知道。
     */
    @Test
    void 不同日期各插一行且countByDate只认当天() {
        Long campaignId = currentCampaignId();
        LocalDate today = LocalDate.now();

        assertThat(checkinMapper.insert(row(campaignId, today))).isEqualTo(1);
        assertThat(checkinMapper.insert(row(campaignId, today.minusDays(1)))).isEqualTo(1);
        assertThat(myRowCount(campaignId)).isEqualTo(2);

        assertThat(checkinService.checkedToday(campaignId, userId))
                .as("countByDate 该只数今天那一行")
                .isTrue();
        assertThat(checkinMapper.countByDate(campaignId, userId, today.minusDays(2)))
                .as("没签的那天必须是 0，否则就是日期条件根本没进 SQL")
                .isZero();
    }

    // ---- 手搓行 ----

    /** 用 current() 不用 requireRunning()：本类不关心排期到没到，只要一个真实的 campaign_id */
    private Long currentCampaignId() {
        Campaign c = campaignService.current();
        assertThat(c).as("库里应有一行 RUNNING 活动，先跑 sql/campaign.sql").isNotNull();
        return c.getId();
    }

    private long myRowCount(Long campaignId) {
        return checkinMapper.listByCampaign(campaignId).stream()
                .filter(r -> r.getUserId() == userId).count();
    }

    private CampaignCheckin row(Long campaignId, LocalDate date) {
        CampaignCheckin r = new CampaignCheckin();
        r.setCampaignId(campaignId);
        r.setUserId(userId);
        r.setCheckinDate(date);
        return r;
    }
}
