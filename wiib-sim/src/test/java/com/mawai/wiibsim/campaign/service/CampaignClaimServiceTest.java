package com.mawai.wiibsim.campaign.service;

import com.mawai.wiibcommon.exception.BizException;
import com.mawai.wiibsim.campaign.LdcProperties;
import com.mawai.wiibsim.campaign.entity.Campaign;
import com.mawai.wiibsim.campaign.entity.CampaignReward;
import com.mawai.wiibsim.campaign.ldc.LdcClient;
import com.mawai.wiibsim.campaign.ldc.LdcResult;
import com.mawai.wiibsim.campaign.mapper.CampaignMapper;
import com.mawai.wiibsim.campaign.mapper.CampaignRewardMapper;
import com.mawai.wiibsim.campaign.mapper.CampaignStatsMapper;
import com.mawai.wiibsim.config.LinuxDoConfig;
import com.mawai.wiibsim.dto.LinuxDoUserInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 领取：二次授权核身份 → CAS 抢领取权 → 调分发接口 → 按结果落状态。
 * 不起 Spring、不连库、<b>绝不连网</b>（RestTemplate 与 LdcClient 都是 mock，
 * 真发放接口一次也不许碰到）。
 * <p>
 * 【为什么 CampaignService 用真的】同 {@link CampaignCheckinServiceTest}：
 * "领取只能用 current() 不能用 requireRunning()"这条规矩就活在 CampaignService 里。
 * mock 掉它，"结算后活动还查不查得到"这件事就测不成了 —— 而那正是领取的前提。
 * <p>
 * 【本类钉的两条顺序不变量】都是能悄悄多花钱 / 少花钱的那种：
 * <ul>
 *   <li><b>身份不符必须在 CAS 与发放之前抛</b> —— 否则一次授权错号会把行推进 CLAIMED，
 *       甚至真把钱打到别人账上。</li>
 *   <li><b>CAS 必须在发网络请求之前</b> —— 否则双击的两个请求会各自发一次 HTTP，
 *       靠单号虽然不会重复出钱，但必有一笔拿到 duplicate key 被判成"此前已发放"、
 *       external_ref 落成 NULL，对账时那笔就成了悬案。</li>
 * </ul>
 */
class CampaignClaimServiceTest {

    private static final long CAMPAIGN_ID = 7L;
    private static final long ME = 1L;
    private static final long REWARD_ID = 55L;
    private static final String OUT_TRADE_NO = "WIIB_FIVEDIM_202608_1";
    private static final BigDecimal AMOUNT = new BigDecimal("300.00");

    /** 我在 LinuxDo 那边的数字 ID：库里存的、这次授权拿回来的，正常情形下必须是同一个 */
    private static final String MY_LINUXDO_ID = "218272";
    private static final String MY_NAME = "mawai";

    private static final String TOKEN_URL = "https://connect.linux.do/oauth2/token";
    private static final String USER_URL = "https://connect.linux.do/api/user";
    private static final String CODE = "oauth-code-xyz";

    private CampaignMapper campaignMapper;
    private CampaignRewardMapper rewardMapper;
    private CampaignStatsMapper statsMapper;
    private RestTemplate restTemplate;
    private LdcClient ldcClient;
    private LdcProperties ldcProperties;
    private CampaignClaimService service;

    @BeforeEach
    void setUp() {
        campaignMapper = mock(CampaignMapper.class);
        rewardMapper = mock(CampaignRewardMapper.class);
        statsMapper = mock(CampaignStatsMapper.class);
        restTemplate = mock(RestTemplate.class);
        ldcClient = mock(LdcClient.class);

        LinuxDoConfig linuxDoConfig = mock(LinuxDoConfig.class);
        when(linuxDoConfig.getTokenUrl()).thenReturn(TOKEN_URL);
        when(linuxDoConfig.getUserUrl()).thenReturn(USER_URL);

        // 真的 LdcProperties：ready() 的三个条件是领取的第一道闸，mock 掉就测不成了
        ldcProperties = new LdcProperties();
        ldcProperties.setEnabled(true);
        ldcProperties.setClientId("cid");
        ldcProperties.setClientSecret("secret");

        service = new CampaignClaimService(new CampaignService(campaignMapper), rewardMapper,
                statsMapper, linuxDoConfig, restTemplate, ldcClient, ldcProperties);

        campaign(Campaign.STATUS_SETTLING);
        stubReward(CampaignReward.PENDING, LocalDateTime.now().minusDays(1));
        stubOauth(Long.parseLong(MY_LINUXDO_ID), MY_NAME);
        when(statsMapper.selectLinuxDoId(ME)).thenReturn(MY_LINUXDO_ID);
        when(rewardMapper.casClaim(eq(REWARD_ID), anyString(), anyString())).thenReturn(1);
    }

    // ==================== 发放成功 ====================

    /** 顺利领到：发放接口给了流水号，落 SUCCESS 并把流水号记进 external_ref 供对账 */
    @Test
    void 领取成功落SUCCESS并记下流水号() {
        when(ldcClient.distribute(MY_LINUXDO_ID, MY_NAME, AMOUNT, OUT_TRADE_NO))
                .thenReturn(LdcResult.ok("87597927423505256"));

        service.claim(ME, CODE);

        verify(rewardMapper).casClaim(REWARD_ID, MY_LINUXDO_ID, MY_NAME);
        verify(rewardMapper).markSuccess(REWARD_ID, "87597927423505256");
        verify(rewardMapper, never()).markFailed(anyLong(), anyString());
    }

    /**
     * ★ 命中单号幂等（success=true 但 tradeNo 为 null）照样算领到了 ★
     * <p>
     * 那意味着钱上次其实已经发出去了（响应没读到、或客户端超时重发），只是这次拿不到新流水号。
     * 判成失败的话用户会看到"发放失败"然后再点一次 —— 每次都会命中同一个幂等，永远领不"成功"，
     * 而钱其实一直躺在他账上。external_ref 落 NULL 是这种情形的正常长相，不是 bug。
     */
    @Test
    void 命中单号幂等也算领取成功() {
        when(ldcClient.distribute(MY_LINUXDO_ID, MY_NAME, AMOUNT, OUT_TRADE_NO))
                .thenReturn(LdcResult.alreadySent());

        service.claim(ME, CODE);

        verify(rewardMapper).markSuccess(REWARD_ID, null);
        verify(rewardMapper, never()).markFailed(anyLong(), anyString());
    }

    /** 发放用的是奖励行上那个固定单号，不是现编的 —— 补发靠它撞唯一索引才不会重复出钱 */
    @Test
    void 发放带的是奖励行上那个固定单号() {
        when(ldcClient.distribute(anyString(), anyString(), any(), anyString()))
                .thenReturn(LdcResult.ok("tn"));

        service.claim(ME, CODE);

        verify(ldcClient).distribute(MY_LINUXDO_ID, MY_NAME, AMOUNT, OUT_TRADE_NO);
    }

    // ==================== 发放失败 ====================

    /**
     * 发放失败落 FAILED 并把原文记下来，同时告诉用户。
     * <p>
     * FAILED 是<b>可以再领一次</b>的状态（casClaim 的 WHERE 收 PENDING 与 FAILED）：
     * 失败多半是收款人不存在这类可修问题，修好了该能重来；而单号不变，
     * 就算上次其实发出去了，重发也会撞唯一索引被判成功，不会重复给钱。
     */
    @Test
    void 发放失败落FAILED记原文并报给用户() {
        when(ldcClient.distribute(anyString(), anyString(), any(), anyString()))
                .thenReturn(LdcResult.fail("HTTP 400 {\"error_msg\":\"收款用户不存在\"}"));

        assertThatThrownBy(() -> service.claim(ME, CODE))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("发放失败")
                .hasMessageContaining("收款用户不存在");

        verify(rewardMapper).markFailed(REWARD_ID, "HTTP 400 {\"error_msg\":\"收款用户不存在\"}");
        verify(rewardMapper, never()).markSuccess(anyLong(), anyString());
    }

    /**
     * ★★ 发放整个抛异常时也必须落 FAILED，不能把行扔在 CLAIMED ★★
     * <p>
     * 【什么时候真会抛】distribute 只把 http.send 包在 try 里，之前那截是裸的：
     * LDC_BASE_URL 拼不成 URI、URI 不是绝对地址、凭证里混进控制字符，都在那儿抛
     * IllegalArgumentException。最可能撞上的时刻是 LDC_ENABLED 第一次打开的那次发版
     * —— 那几个环境变量当时是新的，而这窗口里每个点"领取"的人都会被永久钉在 CLAIMED
     * （casClaim 只收 PENDING/FAILED，claim() 直接拒 CLAIMED，没超时也没自愈，只能人工改库）。
     * <p>
     * 落 FAILED 是无条件安全的：单号一个字没动，请求就算真到了服务端，
     * 重领时同一单号会撞唯一索引被判 SUCCESS，不会重复付款。
     */
    @Test
    void 发放抛异常也落FAILED而不是卡在CLAIMED() {
        when(ldcClient.distribute(anyString(), anyString(), any(), anyString()))
                .thenThrow(new IllegalArgumentException("URI with undefined scheme"));

        assertThatThrownBy(() -> service.claim(ME, CODE))
                .isInstanceOf(BizException.class)
                .hasMessage("发放异常，请稍后重试");

        ArgumentCaptor<String> msg = ArgumentCaptor.forClass(String.class);
        verify(rewardMapper).markFailed(eq(REWARD_ID), msg.capture());
        assertThat(msg.getValue()).contains("URI with undefined scheme");
        verify(rewardMapper, never()).markSuccess(anyLong(), anyString());
    }

    /** FAILED 的行还能再领：casClaim 收它，所以走到发放那一步 */
    @Test
    void 上次失败的行还能再领一次() {
        stubReward(CampaignReward.FAILED, LocalDateTime.now().minusDays(1));
        when(ldcClient.distribute(anyString(), anyString(), any(), anyString()))
                .thenReturn(LdcResult.ok("tn"));

        service.claim(ME, CODE);

        verify(rewardMapper).casClaim(REWARD_ID, MY_LINUXDO_ID, MY_NAME);
        verify(rewardMapper).markSuccess(REWARD_ID, "tn");
    }

    // ==================== 顺序不变量 ====================

    /**
     * ★★ CAS 必须抢在网络请求之前 ★★
     * <p>
     * 反过来的话，双击的两个请求会各自发出一次 HTTP。靠单号服务端不会重复出钱，
     * 但两笔里必有一笔拿到 duplicate key 被判成"此前已发放"，external_ref 落成 NULL
     * —— 对账时那一笔就成了悬案：分不清是真幂等命中，还是压根没发出去。
     */
    @Test
    void 先抢CAS再发网络请求() {
        when(ldcClient.distribute(anyString(), anyString(), any(), anyString()))
                .thenReturn(LdcResult.ok("tn"));

        service.claim(ME, CODE);

        InOrder order = inOrder(rewardMapper, ldcClient);
        order.verify(rewardMapper).casClaim(eq(REWARD_ID), anyString(), anyString());
        order.verify(ldcClient).distribute(anyString(), anyString(), any(), anyString());
    }

    /** 并发双击：第二个请求抢不到 CAS（影响 0 行），当场拒绝，一个字节都不许发出去 */
    @Test
    void 抢不到CAS就不发放() {
        when(rewardMapper.casClaim(eq(REWARD_ID), anyString(), anyString())).thenReturn(0);

        assertThatThrownBy(() -> service.claim(ME, CODE))
                .isInstanceOf(BizException.class)
                .hasMessage("领取状态已变化，请刷新后重试");

        verify(ldcClient, never()).distribute(anyString(), anyString(), any(), anyString());
    }

    /**
     * ★★ 授权的号不是本人的：必须在 CAS 与发放之前就抛 ★★
     * <p>
     * 授权错号是个用户改得了的错（换个号重新授权就行），所以这一行的状态一点都不该动，
     * 更不该真把钱打到那个号上。核的是 linux_do_id 不是 username：
     * 只有 username 会过期，id 是 OAuth 的登录主键、从不变。
     */
    @Test
    void 授权成了别人的号在动状态与发钱之前就拒绝() {
        stubOauth(999999L, "someone-else");

        assertThatThrownBy(() -> service.claim(ME, CODE))
                .isInstanceOf(BizException.class)
                .hasMessage("授权的 LinuxDo 账号与当前登录账号不符，请用本人账号授权");

        verify(rewardMapper, never()).casClaim(anyLong(), anyString(), anyString());
        verify(ldcClient, never()).distribute(anyString(), anyString(), any(), anyString());
    }

    /** 库里压根没绑 LinuxDo（邀请码用户）也走同一条拒绝路径，不是 NPE */
    @Test
    void 库里没绑LinuxDo也被拒绝() {
        when(statsMapper.selectLinuxDoId(ME)).thenReturn(null);

        assertThatThrownBy(() -> service.claim(ME, CODE))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("与当前登录账号不符");

        verify(rewardMapper, never()).casClaim(anyLong(), anyString(), anyString());
    }

    // ==================== 前置闸门 ====================

    /** 超过领取期限就不给领了，且这道闸在授权之前 —— 白跑一趟 OAuth 没意义 */
    @Test
    void 超过领取期限拒绝领取() {
        ldcProperties.setClaimDays(7);
        stubReward(CampaignReward.PENDING, LocalDateTime.now().minusDays(8));

        assertThatThrownBy(() -> service.claim(ME, CODE))
                .isInstanceOf(BizException.class)
                .hasMessage("领取期限已过，请联系管理员");

        verify(restTemplate, never()).postForObject(anyString(), any(), eq(String.class));
        verify(rewardMapper, never()).casClaim(anyLong(), anyString(), anyString());
    }

    /** 期限内的边界：第 7 天还在期限内（claimDays 是"加这么多天之后才算过期"） */
    @Test
    void 期限最后一天仍可领取() {
        ldcProperties.setClaimDays(7);
        stubReward(CampaignReward.PENDING, LocalDateTime.now().minusDays(6));
        when(ldcClient.distribute(anyString(), anyString(), any(), anyString()))
                .thenReturn(LdcResult.ok("tn"));

        service.claim(ME, CODE);

        verify(rewardMapper).markSuccess(REWARD_ID, "tn");
    }

    /** 活动还没结算（还是 RUNNING）时不给领 */
    @Test
    void 活动没结算时拒绝领取() {
        campaign(Campaign.STATUS_RUNNING);

        assertThatThrownBy(() -> service.claim(ME, CODE))
                .isInstanceOf(BizException.class)
                .hasMessage("活动尚未结算，暂不可领取");

        verify(rewardMapper, never()).casClaim(anyLong(), anyString(), anyString());
    }

    /** 活动都查不到了（DONE，selectActive 不收）同样不给领 */
    @Test
    void 没有活动时拒绝领取() {
        when(campaignMapper.selectActive()).thenReturn(null);

        assertThatThrownBy(() -> service.claim(ME, CODE))
                .isInstanceOf(BizException.class)
                .hasMessage("活动尚未结算，暂不可领取");
    }

    /** 凭证没配全（enabled 开着但 secret 空）时，领取按钮点下去要报"未开启"而不是去撞接口 */
    @Test
    void 发放未启用时拒绝领取() {
        ldcProperties.setClientSecret("");

        assertThatThrownBy(() -> service.claim(ME, CODE))
                .isInstanceOf(BizException.class)
                .hasMessage("发放功能未开启");

        verify(ldcClient, never()).distribute(anyString(), anyString(), any(), anyString());
    }

    /** 不在奖励名单里 */
    @Test
    void 没有奖励行时拒绝领取() {
        when(rewardMapper.selectMine(CAMPAIGN_ID, ME)).thenReturn(null);

        assertThatThrownBy(() -> service.claim(ME, CODE))
                .isInstanceOf(BizException.class)
                .hasMessage("你没有可领取的奖励");
    }

    /** 已经到账的不许再领 —— 再领一次会白撞一次幂等，还给用户"又领了一遍"的错觉 */
    @Test
    void 已经领过的不许再领() {
        stubReward(CampaignReward.SUCCESS, LocalDateTime.now().minusDays(1));

        assertThatThrownBy(() -> service.claim(ME, CODE))
                .isInstanceOf(BizException.class)
                .hasMessage("已经领取过了");

        verify(ldcClient, never()).distribute(anyString(), anyString(), any(), anyString());
    }

    /**
     * CLAIMED 表示上一次的发放请求还在飞（最坏要 ~2 分钟：8 次重试 × 15s 超时）。
     * 这时再点一次不该并发发第二笔，让用户等结果。
     */
    @Test
    void 上一次还在处理中不许重复点() {
        stubReward(CampaignReward.CLAIMED, LocalDateTime.now().minusDays(1));

        assertThatThrownBy(() -> service.claim(ME, CODE))
                .isInstanceOf(BizException.class)
                .hasMessage("上一次领取正在处理中，请稍后再看");

        verify(ldcClient, never()).distribute(anyString(), anyString(), any(), anyString());
    }

    // ==================== 我的奖励 ====================

    /** 活动结算后（SETTLING）仍查得到活动，所以领取入口拿得到这一行 */
    @Test
    void 我的奖励在结算后查得到() {
        assertThat(service.myReward(ME)).isNotNull();
    }

    /** 没有活动时返回 null（前端据此隐藏领取入口），不是抛异常 */
    @Test
    void 没有活动时我的奖励返回null() {
        when(campaignMapper.selectActive()).thenReturn(null);

        assertThat(service.myReward(ME)).isNull();
        verify(rewardMapper, never()).selectMine(anyLong(), anyLong());
    }

    // ==================== 手搓行 ====================

    private void campaign(String status) {
        Campaign c = new Campaign();
        c.setId(CAMPAIGN_ID);
        c.setCode("FIVEDIM_202608");
        c.setStartAt(LocalDateTime.now().minusDays(20));
        c.setEndAt(LocalDateTime.now().minusDays(1));
        c.setPrizePool(new BigDecimal("500"));
        c.setStatus(status);
        when(campaignMapper.selectActive()).thenReturn(c);
    }

    private void stubReward(String status, LocalDateTime createdAt) {
        CampaignReward r = new CampaignReward();
        r.setId(REWARD_ID);
        r.setCampaignId(CAMPAIGN_ID);
        r.setUserId(ME);
        r.setLdcAmount(AMOUNT);
        r.setStatus(status);
        r.setOutTradeNo(OUT_TRADE_NO);
        r.setCreatedAt(createdAt);
        when(rewardMapper.selectMine(CAMPAIGN_ID, ME)).thenReturn(r);
    }

    /** 二次授权那两跳：code 换 token、token 换用户信息。全程 mock，一个包都不发出去 */
    private void stubOauth(long linuxDoId, String username) {
        when(restTemplate.postForObject(eq(TOKEN_URL), any(HttpEntity.class), eq(String.class)))
                .thenReturn("{\"access_token\":\"tok\"}");

        LinuxDoUserInfo info = new LinuxDoUserInfo();
        info.setId(linuxDoId);
        info.setUsername(username);
        when(restTemplate.exchange(eq(USER_URL), eq(HttpMethod.GET), any(HttpEntity.class),
                eq(LinuxDoUserInfo.class))).thenReturn(ResponseEntity.ok(info));
    }
}
