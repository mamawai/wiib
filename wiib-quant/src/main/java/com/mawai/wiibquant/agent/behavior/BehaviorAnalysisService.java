package com.mawai.wiibquant.agent.behavior;

import com.alibaba.fastjson2.JSON;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.mawai.wiibcommon.util.JsonUtils;
import com.mawai.wiibcommon.util.Result;
import com.mawai.wiibquant.agent.config.AiAgentRuntimeManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.StateGraph;
import org.bsc.langgraph4j.prebuilt.MessagesState;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Service;

import java.util.Map;
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
    // 失败负缓存：失败不进 reportCache 的话，用户每点一次重试就全额烧一遍 agent（ReAct 十几次
    // 工具调用）且永远烧不出缓存。失败也短存，把重试风暴钝化成每 2 分钟最多一次真跑；
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

        StateGraph<MessagesState<Message>> agent;
        try {
            agent = aiAgentRuntimeManager.createBehaviorAgent(step -> log.info("用户{} 工具调用: {}", userId, step));
        } catch (Exception e) {
            log.error("行为分析 agent 构建失败 userId={}", userId, e);
            return Result.fail("分析执行失败: " + e.getMessage());
        }

        String text;
        try {
            text = collectResponse(agent, userId);
        } catch (Exception e) {
            log.error("行为分析执行失败 userId={}", userId, e);
            return Result.fail("分析执行失败: " + e.getMessage());
        }

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

    /** 阻塞跑完整个 ReAct 循环，取最终助手消息——中间的工具调用轮次文本为空，不参与结果。 */
    private String collectResponse(StateGraph<MessagesState<Message>> agent, long userId) throws Exception {
        String prompt = "分析用户#" + userId + "的全部行为数据，用户ID为" + userId;

        String finalText = agent.compile()
                .invoke(Map.of("messages", new UserMessage(prompt)),
                        RunnableConfig.builder().threadId("behavior-" + userId).build())
                .flatMap(MessagesState::lastMessage)
                .map(Message::getText)
                .orElse("");

        if (finalText.isBlank()) {
            throw new IllegalStateException("行为分析未返回有效内容");
        }
        log.info("用户{} 行为分析完成, responseLength={}", userId, finalText.length());
        return finalText;
    }
}
