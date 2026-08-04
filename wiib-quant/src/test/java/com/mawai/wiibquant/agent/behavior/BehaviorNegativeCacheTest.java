package com.mawai.wiibquant.agent.behavior;

import com.mawai.wiibquant.agent.config.AiAgentRuntimeManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 失败负缓存：LLM 分析失败不进成功缓存，用户每点一次重试就全额烧一遍 agent
 * （ReAct 十几次工具调用），且永远烧不出缓存。失败结果短存后，TTL 内重试快速返回，
 * 真跑频率被钝化成"每个负缓存周期最多一次"。信号量拒绝（人数已满）是瞬时负载，不进负缓存。
 */
class BehaviorNegativeCacheTest {

    private AiAgentRuntimeManager runtimeManager;
    private BehaviorAnalysisService service;

    @BeforeEach
    void setUp() throws Exception {
        runtimeManager = mock(AiAgentRuntimeManager.class);
        // agent 构建即失败：最短路径触发 doAnalyze 失败，不用 mock 整个图执行
        when(runtimeManager.createBehaviorAgent(any())).thenThrow(new RuntimeException("模型不可用"));
        service = new BehaviorAnalysisService(runtimeManager);
    }

    @Test
    void 失败负缓存_TTL内重试不再烧agent() throws Exception {
        var first = service.analyze(7L);
        assertThat(first.getCode()).isNotZero();

        var second = service.analyze(7L);
        assertThat(second.getCode()).isNotZero();
        assertThat(second.getMsg()).isNotBlank();

        // 第二次没有再构建/运行 agent——这是负缓存的全部意义
        verify(runtimeManager, times(1)).createBehaviorAgent(any());
    }

    @Test
    void 负缓存按用户隔离_不互相污染() throws Exception {
        service.analyze(7L);
        service.analyze(8L);
        // 各自烧各自的一次，7 的失败不挡 8
        verify(runtimeManager, times(2)).createBehaviorAgent(any());
    }
}
