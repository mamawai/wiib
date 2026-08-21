package com.mawai.wiibquant.agent.behavior;

import com.alibaba.fastjson2.JSON;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.mawai.wiibcommon.enums.AgentLang;
import com.mawai.wiibcommon.util.JsonUtils;
import com.mawai.wiibcommon.util.Result;
import com.mawai.wiibquant.agent.i18n.UserLangResolver;
import com.mawai.wiibquant.agent.runtime.AiAgentRuntimeManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class BehaviorAnalysisService {

    private final AiAgentRuntimeManager aiAgentRuntimeManager;
    private final UserLangResolver userLangResolver;

    /** 缓存按语言分格：报告正文是模型用某门语言写的，换了语言那份就不能再用 */
    private record CacheKey(long userId, AgentLang lang) {
    }

    private final Semaphore behaviorSemaphore = new Semaphore(10);
    private final Cache<CacheKey, BehaviorAnalysisReport> reportCache = Caffeine.newBuilder()
            .expireAfterWrite(30, TimeUnit.MINUTES)
            .maximumSize(10_000)
            .build();
    // 失败负缓存：失败不进 reportCache 的话，用户每点一次重试就全额烧一遍分析（10 个端点 + 一次
    // 大 prompt 的 LLM 调用）且永远烧不出缓存。失败也短存，把重试风暴钝化成每 2 分钟最多一次真跑；
    // TTL 刻意远短于成功缓存——给上游（模型/网络）故障恢复留窗口
    private final Cache<CacheKey, String> failCache = Caffeine.newBuilder()
            .expireAfterWrite(2, TimeUnit.MINUTES)
            .maximumSize(10_000)
            .build();

    public Result<BehaviorAnalysisReport> analyze(long userId) {
        CacheKey key = new CacheKey(userId, userLangResolver.of(userId));

        BehaviorAnalysisReport cached = reportCache.getIfPresent(key);
        if (cached != null) {
            return Result.ok(cached);
        }
        String recentFail = failCache.getIfPresent(key);
        if (recentFail != null) {
            return Result.fail(recentFail);
        }

        if (!behaviorSemaphore.tryAcquire()) {
            // 瞬时负载不是故障：不进负缓存，下一秒就可能有空位
            return Result.fail("当前分析人数已满，请稍后再试");
        }
        try {
            Result<BehaviorAnalysisReport> result = doAnalyze(key);
            if (result.getCode() == 0 && result.getData() != null) {
                reportCache.put(key, result.getData());
            } else {
                failCache.put(key, result.getMsg());
            }
            return result;
        } finally {
            behaviorSemaphore.release();
        }
    }

    private Result<BehaviorAnalysisReport> doAnalyze(CacheKey key) {
        long userId = key.userId();
        log.info("用户{}请求行为分析，语言{}", userId, key.lang().code());

        String text;
        try {
            text = aiAgentRuntimeManager.runBehaviorAnalysis(userId, key.lang(),
                    step -> log.info("用户{} 行为分析进度: {}", userId, step));
        } catch (Exception e) {
            log.error("行为分析执行失败 userId={}", userId, e);
            return Result.fail("分析执行失败: " + e.getMessage());
        }
        log.info("用户{} 行为分析模型返回, responseLength={}", userId, text.length());

        BehaviorAnalysisReport report;
        try {
            report = JSON.parseObject(JsonUtils.extractJson(text), BehaviorAnalysisReport.class);
        } catch (Exception e) {
            log.error("行为分析报告解析失败 userId={}", userId, e);
            return Result.fail("分析结果解析失败");
        }

        if (!report.isValid()) {
            log.error("行为分析关键字段缺失 userId={}", userId);
            return Result.fail("分析结果不完整，请重试");
        }

        log.info("用户{} 行为分析完成", userId);
        return Result.ok(report);
    }
}
