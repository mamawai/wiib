package com.mawai.wiibsim.campaign;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.mawai.wiibcommon.exception.BizException;
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
 * Java 那一侧（插的行对不对、异常翻成什么话、积分怎么算）由
 * {@link com.mawai.wiibsim.campaign.service.CampaignCheckinServiceTest} 管，这里不重复。
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
        Campaign c = campaignService.requireRunning();
        LocalDate today = LocalDate.now();

        assertThat(checkinMapper.insert(row(c.getId(), today))).isEqualTo(1);

        assertThatThrownBy(() -> checkinMapper.insert(row(c.getId(), today)))
                .as("uk_campaign_checkin 没建上，或异常没被翻译成 Spring 的 DuplicateKeyException")
                .isInstanceOf(DuplicateKeyException.class);

        // 顶回来之后库里仍然只有一行，不是"报了错但也插进去了"
        assertThat(checkinMapper.listByCampaign(c.getId()).stream()
                .filter(r -> r.getUserId() == userId).count()).isEqualTo(1);
    }

    /**
     * 服务这一层：第二次签到得是一句人话，不是 500。
     * 顺带把 countByDate 那条 SQL 也真发一次 —— 注解 SQL 写错了只有跑起来才知道。
     */
    @Test
    void 第二次签到报今天已经签到过了() {
        Long campaignId = campaignService.requireRunning().getId();

        assertThat(checkinService.checkin(userId))
                .as("新用户第一次签到，最长连续段就是 1 天")
                .isEqualTo(1);
        assertThat(checkinService.checkedToday(campaignId, userId)).isTrue();

        assertThatThrownBy(() -> checkinService.checkin(userId))
                .isInstanceOf(BizException.class)
                .hasMessage("今天已经签到过了");
    }

    private CampaignCheckin row(Long campaignId, LocalDate date) {
        CampaignCheckin r = new CampaignCheckin();
        r.setCampaignId(campaignId);
        r.setUserId(userId);
        r.setCheckinDate(date);
        return r;
    }
}
