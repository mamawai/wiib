package com.mawai.wiibquant.agent.behavior;

import com.alibaba.fastjson2.JSON;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.mawai.wiibcommon.util.JsonUtils;
import com.mawai.wiibcommon.util.Result;
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

    private final Semaphore behaviorSemaphore = new Semaphore(10);
    private final Cache<Long, BehaviorAnalysisReport> reportCache = Caffeine.newBuilder()
            .expireAfterWrite(30, TimeUnit.MINUTES)
            .maximumSize(10_000)
            .build();
    // 失败负缓存：失败不进 reportCache 的话，用户每点一次重试就全额烧一遍分析（10 个端点 + 一次
    // 大 prompt 的 LLM 调用）且永远烧不出缓存。失败也短存，把重试风暴钝化成每 2 分钟最多一次真跑；
    // TTL 刻意远短于成功缓存——给上游（模型/网络）故障恢复留窗口
    private final Cache<Long, String> failCache = Caffeine.newBuilder()
            .expireAfterWrite(2, TimeUnit.MINUTES)
            .maximumSize(10_000)
            .build();

    public Result<BehaviorAnalysisReport> analyze(long userId) {
        BehaviorAnalysisReport cached = reportCache.getIfPresent(userId);
        if (cached != null) {
            return Result.ok(cached);
        }
        String recentFail = failCache.getIfPresent(userId);
        if (recentFail != null) {
            return Result.fail(recentFail);
        }

        if (!behaviorSemaphore.tryAcquire()) {
            // 瞬时负载不是故障：不进负缓存，下一秒就可能有空位
            return Result.fail("当前分析人数已满，请稍后再试");
        }
        try {
            Result<BehaviorAnalysisReport> result = doAnalyze(userId);
            if (result.getCode() == 0 && result.getData() != null) {
                reportCache.put(userId, result.getData());
            } else {
                failCache.put(userId, result.getMsg());
            }
            return result;
        } finally {
            behaviorSemaphore.release();
        }
    }

    private Result<BehaviorAnalysisReport> doAnalyze(long userId) {
        log.info("用户{}请求行为分析", userId);

        String text;
        try {
            text = aiAgentRuntimeManager.runBehaviorAnalysis(userId,
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
