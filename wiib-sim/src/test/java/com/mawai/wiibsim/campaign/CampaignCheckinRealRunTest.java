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
 * 【这个类唯一要证的事】"一天只能签一次"这条铁律的执行者是数据库的 uk_campaign_checkin
 * 唯一索引，而 {@link CampaignCheckinService#checkin} 是靠 catch
 * {@link DuplicateKeyException} 接住它的。这条链上有<b>两处只有真库才验得了</b>的环节：
 * <ol>
 *   <li>DDL 里的唯一约束真的建上了（{@code sql/campaign.sql} 没落库、或建表时漏了那行，
 *       表照样能用，只是重复签到静默插进两条 —— 每人白拿一分）；</li>
 *   <li>PG 抛的 SQLState 23505 真的被 MyBatis 的异常翻译器转成了 Spring 的
 *       DuplicateKeyException。翻译器没装或翻成别的类型，那个 catch 就是<b>死代码</b>，
 *       用户看到的是 500 "系统异常"。</li>
 * </ol>
 * mock 掉 mapper 这两条一条都验不了：假 mapper 上让它抛什么就抛什么，测试永远绿。
 * <p>
 * 【为什么不走 checkin 而是直接调 mapper 插】requireRunning 现在带时间窗
 * [startAt, endAt)，而种子活动的排期是 2026-08-03 ~ 2026-08-17 —— 排期外的日子跑本类，
 * checkin 会先被闸门挡住，根本走不到唯一索引那一步。
 * 想让 checkin 跑通就得另插一场覆盖"此刻"的活动，但 {@code selectActive} 是
 * {@code ORDER BY start_at DESC LIMIT 1}：要压过种子行，新活动的 start_at 必须晚于
 * 2026-08-03，而要覆盖此刻又必须早于此刻 —— 排期未到时这两个条件互斥，做不到；
 * 硬塞一场 RUNNING 活动还会顺带改掉真库上 {@code current()} 的答案，风险换不来收益。
 * 所以本类只钉"只有真库才证得了"的那一半（约束在 + 异常翻译对），
 * 另一半（DuplicateKeyException → BizException("今天已经签到过了")）由
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
 * 【写真库的自律】连的是所有者真实开发库，所以 user_id 一律取<b>负数</b>
 * （真 user.id 是自增正数，负号保证绝不与真人撞车，清理时也不会误删真行），
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
