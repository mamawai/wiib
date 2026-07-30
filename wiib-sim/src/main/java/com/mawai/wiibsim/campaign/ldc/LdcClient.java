package com.mawai.wiibsim.campaign.ldc;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.mawai.wiibsim.campaign.LdcProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;

/**
 * LinuxDo 积分分发客户端。
 * <p>
 * 全部判定依据来自 2026-07-31 的真实调用验证（设计文档 §6.2），
 * <b>官方文档部分内容与实际不符，以此处为准</b>：
 * <ul>
 *   <li>成功 = HTTP 200 且 {@code data.trade_no} 非空。文档 3.4 写的 {@code {"code":1}} 是错的</li>
 *   <li>失败 = HTTP 400 + {@code {"error_msg":"...","data":null}}</li>
 *   <li>重复单号 = HTTP 400 + PostgreSQL 原始报错 {@code duplicate key ... (SQLSTATE 23505)}</li>
 * </ul>
 */
@Slf4j
@RequiredArgsConstructor
@org.springframework.stereotype.Component
public class LdcClient {

    /** 分发备注，收款人在自己的积分流水里看到的说明。写死即可，本活动只有一场 */
    private static final String REMARK = "WhatIfIBought 五维交易赛奖励";

    /** 307 重试上限。单次误路由概率约 50%，重试 8 次全失败概率 0.4% */
    private static final int MAX_ATTEMPTS = 8;

    // 退避参数。非 final 且包内可见只为一件事：单测把它调到毫秒级，
    // 否则「307 重试到上限」一条要真睡 16.5 秒。生产值就是下面这两个数，别改
    static long initialBackoffMs = 300;
    static long maxBackoffMs = 4000;

    private final LdcProperties props;

    /**
     * 【必须 NEVER】服务端约 50% 的请求会被误路由到前端返回 307，
     * 跟过去只能拿到一坨登录页 HTML —— 那时根本分不清"没发出去"还是"发出去了没读到响应"。
     * 拒绝跟随，307 才能被识别成"请求压根没到后端"，从而安全重试。
     * <p>
     * JDK 的默认值本就是 NEVER（不同于 RestTemplate/WebClient 这类会跟随的客户端），
     * 这行是把"依赖它"写成明面上的约定，别人顺手删掉时能看见代价。
     */
    private final HttpClient http = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    /**
     * 发放。同一个 outTradeNo 重复调用是安全的：服务端靠唯一索引挡住，
     * 撞上就说明上次已经发成功了，这里判 SUCCESS。
     *
     * @param linuxDoId 收款人 LinuxDo 数字 ID（领取时二次 OAuth 拿到的最新值）
     * @param username  收款人用户名，服务端做二次校验
     * @param amount    金额，最多 2 位小数
     * @param outTradeNo 商户单号 WIIB_{campaignCode}_{userId}，固定可重算
     */
    public LdcResult distribute(String linuxDoId, String username, BigDecimal amount, String outTradeNo) {
        if (!props.ready()) return LdcResult.fail("LDC 发放未启用或凭证未配置");

        // amount 最多两位小数，超了服务端直接拒
        String amountStr = amount.setScale(2, RoundingMode.DOWN).toPlainString();

        // 字段按官方文档 §3.4。user_id 发 JSON number 不发字符串——文档写明是"数字"，
        // 后端 Go 侧若声明成 int64，收到 "12345" 会直接 unmarshal 失败
        long numericUserId;
        try {
            numericUserId = Long.parseLong(linuxDoId);
        } catch (NumberFormatException e) {
            return LdcResult.fail("收款人 LinuxDo ID 不是数字: " + linuxDoId);
        }

        JSONObject payload = new JSONObject();
        payload.put("user_id", numericUserId);
        payload.put("username", username);
        payload.put("amount", amountStr);
        // 文档标"选填"，但我们每次必传：整套幂等就架在这个单号上
        payload.put("out_trade_no", outTradeNo);
        payload.put("remark", REMARK);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(props.getBaseUrl() + "/lpay/distribute"))
                .timeout(Duration.ofSeconds(15))
                .header("Authorization", basicAuth())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload.toJSONString(), StandardCharsets.UTF_8))
                .build();

        String lastError = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                HttpResponse<String> resp = http.send(request, HttpResponse.BodyHandlers.ofString());

                if (resp.statusCode() == 307) {
                    // 前端误路由，后端没收到，重试绝对安全
                    lastError = "307 被误路由到前端，重试 " + attempt + "/" + MAX_ATTEMPTS;
                    log.warn("LDC 分发 {}: {}", outTradeNo, lastError);
                    if (attempt < MAX_ATTEMPTS) sleepBackoff(attempt);
                    continue;
                }
                return judge(resp.statusCode(), resp.body(), outTradeNo);

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return LdcResult.fail("发放被中断");
            } catch (Exception e) {
                // 超时/IO：钱可能已经发出去了，但同一 outTradeNo 重发会撞唯一索引并被判成功，
                // 所以重试是安全的，不会重复发放
                lastError = e.getClass().getSimpleName() + ": " + e.getMessage();
                log.warn("LDC 分发 {} 第 {} 次异常: {}", outTradeNo, attempt, lastError);
                if (attempt < MAX_ATTEMPTS) sleepBackoff(attempt);
            }
        }
        return LdcResult.fail(lastError);
    }

    /**
     * 判定表（设计文档 §6.3）：
     * 200 且 trade_no 非空 → 成功；含 duplicate key / 23505 → 此前已发放，也算成功；
     * 其余 → 失败，记原文不重试。
     */
    private LdcResult judge(int status, String body, String outTradeNo) {
        String raw = body == null ? "" : body;

        if (raw.contains("duplicate key") || raw.contains("23505")) {
            log.info("LDC 分发 {} 命中单号幂等，此前已发放成功", outTradeNo);
            return LdcResult.alreadySent();
        }

        if (status == 200) {
            try {
                JSONObject json = JSON.parseObject(raw);
                JSONObject data = json == null ? null : json.getJSONObject("data");
                String tradeNo = data == null ? null : data.getString("trade_no");
                if (tradeNo != null && !tradeNo.isBlank()) {
                    log.info("LDC 分发成功 {} trade_no={}", outTradeNo, tradeNo);
                    return LdcResult.ok(tradeNo);
                }
            } catch (Exception e) {
                return LdcResult.fail("响应解析失败: " + raw);
            }
            return LdcResult.fail("HTTP 200 但没有 trade_no: " + raw);
        }

        return LdcResult.fail("HTTP " + status + " " + raw);
    }

    private String basicAuth() {
        String cred = props.getClientId() + ":" + props.getClientSecret();
        return "Basic " + Base64.getEncoder().encodeToString(cred.getBytes(StandardCharsets.UTF_8));
    }

    /** 位移退避，封顶 4s。同 ResponsesChatModel:130-138 的范式 */
    private static void sleepBackoff(int attempt) {
        try {
            Thread.sleep(Math.min(initialBackoffMs << (attempt - 1), maxBackoffMs));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
