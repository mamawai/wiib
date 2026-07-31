package com.mawai.wiibsim.campaign.service;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.mawai.wiibcommon.exception.BizException;
import com.mawai.wiibsim.campaign.LdcProperties;
import com.mawai.wiibsim.campaign.entity.Campaign;
import com.mawai.wiibsim.campaign.entity.CampaignReward;
import com.mawai.wiibsim.campaign.ldc.LdcClient;
import com.mawai.wiibsim.campaign.ldc.LdcResult;
import com.mawai.wiibsim.campaign.mapper.CampaignRewardMapper;
import com.mawai.wiibsim.campaign.mapper.CampaignStatsMapper;
import com.mawai.wiibsim.config.LinuxDoConfig;
import com.mawai.wiibsim.dto.LinuxDoUserInfo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDateTime;

/**
 * 领取：二次 LinuxDo 授权 → 拿最新身份 → 调分发接口。
 * <p>
 * 【为什么要二次授权】AuthServiceImpl:134 有条路径：LinuxDo 侧改名撞上本地注册用户名时，
 * 平台保留旧名照常登录。而分发接口拿 username 做二次校验，用库里那个旧名会失败。
 * 走一次 OAuth 直接拿回调里的 username，彻底绕开这个问题。
 * <p>
 * 【为什么仍要核对 id】只有 username 会过期，linux_do_id 是 OAuth 的登录主键、从不变。
 * 核对它能把"授权错了账号"变成一个明确的报错，而不是把钱静默发到别人那儿。
 * <p>
 * 【为什么自己换 token 不复用 AuthServiceImpl】那两个方法是 private，
 * 且活动要整包可删，不许往业务类里加公开方法。这里三十行，复制得起。
 * <p>
 * <b>【本类刻意没有 @Transactional】</b>见 {@link #claim} 里那段注释 —— 加上就同时破坏
 * "CAS 抢锁对别的请求可见"和"发放失败要记 FAILED"两件事。
 */
@Slf4j
@Service
public class CampaignClaimService {

    private final CampaignService campaignService;
    private final CampaignRewardMapper rewardMapper;
    private final CampaignStatsMapper statsMapper;
    private final LinuxDoConfig linuxDoConfig;
    private final RestTemplate linuxDoRestTemplate;
    private final LdcClient ldcClient;
    private final LdcProperties ldcProperties;

    public CampaignClaimService(CampaignService campaignService,
                                CampaignRewardMapper rewardMapper,
                                CampaignStatsMapper statsMapper,
                                LinuxDoConfig linuxDoConfig,
                                @Qualifier("linuxDoRestTemplate") RestTemplate linuxDoRestTemplate,
                                LdcClient ldcClient,
                                LdcProperties ldcProperties) {
        this.campaignService = campaignService;
        this.rewardMapper = rewardMapper;
        this.statsMapper = statsMapper;
        this.linuxDoConfig = linuxDoConfig;
        this.linuxDoRestTemplate = linuxDoRestTemplate;
        this.ldcClient = ldcClient;
        this.ldcProperties = ldcProperties;
    }

    /** 我的奖励行；未结算或不在名单里返回 null */
    public CampaignReward myReward(Long userId) {
        Campaign c = campaignService.current();
        return c == null ? null : rewardMapper.selectMine(c.getId(), userId);
    }

    /**
     * 领取。code 是 /login 页拿到的 OAuth 授权码。
     * <p>
     * 三重防重复：UNIQUE(campaign_id,user_id) + casClaim 的状态检查 + out_trade_no 服务端幂等。
     * <p>
     * 【顺序：先 CAS 再发钱】CAS 是这三重里唯一挡得住"同一个人双击两下"的那道
     * —— 唯一索引管的是"一个人只有一行"，服务端幂等要等请求到了对面才生效。
     * 先发后 CAS 的话，两个并发请求会各自发出一次 HTTP，虽然靠单号不会重复出钱，
     * 但两笔里必有一笔拿到 duplicate key 被判成 alreadySent()，external_ref 落成 NULL，
     * 对账时那笔就成了悬案。
     * <p>
     * <b>【为什么整个方法不能加 @Transactional】</b>两件事都会坏：
     * ① 事务里的 casClaim 在提交前对别的连接不可见，双击的第二个请求照样抢得到锁；
     * ② 发放失败时 markFailed 之后要抛 BizException 告诉用户，而事务会把这次 markFailed
     * 一起回滚掉 —— 库里还是 CLAIMED，错误信息丢了，用户还被卡在"上一次领取正在处理中"。
     * 三次写各自独立提交才是对的，它们本来就靠 CAS 的状态机而不是事务保证一致。
     * <p>
     * 【为什么敢在 Web 线程上同步等】{@link LdcClient#distribute} 最坏要 ~2 分钟
     * （8 次重试 × 15s 超时）。但虚拟线程开着（spring.threads.virtual.enabled），
     * 阻塞不占平台线程；而且真超时了也能收场：out_trade_no 不变，用户再点一次会重发同一单号，
     * 撞上 duplicate key 就证明上一次其实发成功了。~100 人的活动为此改异步 + 轮询不值。
     */
    public CampaignReward claim(Long userId, String code) {
        if (!ldcProperties.ready()) throw new BizException("发放功能未开启");

        // 用 current() 不用 requireRunning()：领取发生在活动结束之后，requireRunning() 那时必抛
        Campaign c = campaignService.current();
        if (c == null || !Campaign.STATUS_SETTLING.equals(c.getStatus())) {
            throw new BizException("活动尚未结算，暂不可领取");
        }

        CampaignReward reward = rewardMapper.selectMine(c.getId(), userId);
        if (reward == null) throw new BizException("你没有可领取的奖励");
        if (CampaignReward.SUCCESS.equals(reward.getStatus())) throw new BizException("已经领取过了");
        if (CampaignReward.CLAIMED.equals(reward.getStatus())) throw new BizException("上一次领取正在处理中，请稍后再看");
        if (reward.getCreatedAt().plusDays(ldcProperties.getClaimDays()).isBefore(LocalDateTime.now())) {
            throw new BizException("领取期限已过，请联系管理员");
        }

        // 身份核对必须在 CAS 与发放之前：授权错号是个能改的错，不该把这一行推进 CLAIMED、
        // 更不该真把钱打出去。这两步之前抛，用户换个号重新授权就行，状态一点没动
        LinuxDoUserInfo info = fetchUserInfo(code);
        String authorizedId = String.valueOf(info.getId());

        String bound = statsMapper.selectLinuxDoId(userId);
        if (bound == null || !bound.equals(authorizedId)) {
            throw new BizException("授权的 LinuxDo 账号与当前登录账号不符，请用本人账号授权");
        }

        if (rewardMapper.casClaim(reward.getId(), authorizedId, info.getUsername()) == 0) {
            throw new BizException("领取状态已变化，请刷新后重试");
        }

        LdcResult result = ldcClient.distribute(
                authorizedId, info.getUsername(), reward.getLdcAmount(), reward.getOutTradeNo());

        if (result.success()) {
            // tradeNo 为 null 是"命中单号幂等、此前已发放成功"（LdcResult.alreadySent），
            // 照样算领到了 —— 钱确实在对面账上，只是这次没拿到新流水号
            rewardMapper.markSuccess(reward.getId(), result.tradeNo());
            log.info("活动奖励发放成功 userId={} amount={} trade_no={}",
                    userId, reward.getLdcAmount(), result.tradeNo());
        } else {
            rewardMapper.markFailed(reward.getId(), result.errorMsg());
            log.warn("活动奖励发放失败 userId={} : {}", userId, result.errorMsg());
            throw new BizException("发放失败：" + result.errorMsg());
        }
        return rewardMapper.selectMine(c.getId(), userId);
    }

    /** code 换 token 再拉用户信息。写法照 AuthServiceImpl:253-318，只是不建号不登录 */
    private LinuxDoUserInfo fetchUserInfo(String code) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "authorization_code");
        form.add("code", code);
        form.add("client_id", linuxDoConfig.getClientId());
        form.add("client_secret", linuxDoConfig.getClientSecret());
        form.add("redirect_uri", linuxDoConfig.getRedirectUri());

        HttpHeaders tokenHeaders = new HttpHeaders();
        tokenHeaders.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

        String tokenResp;
        try {
            tokenResp = linuxDoRestTemplate.postForObject(
                    linuxDoConfig.getTokenUrl(), new HttpEntity<>(form, tokenHeaders), String.class);
        } catch (RestClientException e) {
            throw new BizException("授权失败：" + e.getMessage());
        }
        JSONObject json = JSONUtil.parseObj(tokenResp == null ? "{}" : tokenResp);
        String accessToken = json.getStr("access_token");
        if (accessToken == null) throw new BizException("授权失败：拿不到 access_token");

        HttpHeaders userHeaders = new HttpHeaders();
        userHeaders.set("Authorization", "Bearer " + accessToken);
        LinuxDoUserInfo info;
        try {
            info = linuxDoRestTemplate.exchange(linuxDoConfig.getUserUrl(), HttpMethod.GET,
                    new HttpEntity<>(userHeaders), LinuxDoUserInfo.class).getBody();
        } catch (RestClientException e) {
            throw new BizException("获取 LinuxDo 用户信息失败：" + e.getMessage());
        }
        if (info == null || info.getId() == null) throw new BizException("获取 LinuxDo 用户信息失败：id 为空");
        return info;
    }
}
