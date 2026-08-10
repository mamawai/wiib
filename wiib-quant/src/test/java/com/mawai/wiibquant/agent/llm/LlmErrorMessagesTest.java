package com.mawai.wiibquant.agent.llm;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * BYOK 之后最高频的用户故障就是 key 相关，错误文案的质量直接决定用户能不能自己解决。
 * 而 SDK 原始异常可能是几百字符、带 URL 和请求片段的东西。
 */
class LlmErrorMessagesTest {

    @Test
    void 认证失败给出可操作的提示() {
        assertThat(LlmErrorMessages.classify(new RuntimeException("HTTP 401 Unauthorized: invalid api key")))
                .contains("API key");
    }

    @Test
    void 限流与额度用尽单独归类() {
        assertThat(LlmErrorMessages.classify(new RuntimeException("429 Too Many Requests")))
                .contains("额度");
    }

    /** 断"重新选择"而不是断"模型"：兜底文案里也有"模型"二字，删掉这条分支照样绿 */
    @Test
    void 模型不存在提示重选() {
        assertThat(LlmErrorMessages.classify(new RuntimeException("404 model 'gpt-9' not found")))
                .contains("重新选择");
    }

    /**
     * message 里不能带 timeout 字样，否则同时命中 instanceof 和 contains 两条判断，
     * 删掉 instanceof 那半边测试照样绿，这条分支就没被隔离到
     */
    @Test
    void 连不上时提示检查端点() {
        assertThat(LlmErrorMessages.classify(new java.net.ConnectException("")))
                .contains("Base URL");
    }

    /** 真正的原因常被 SDK/框架包在 cause 里，外层 message 非空但泛泛，不能只看最外层 */
    @Test
    void 从因果链里认出真正的原因() {
        Throwable wrapped = new java.util.concurrent.CompletionException(
                "org.bsc.langgraph4j.GraphRunnerException: node execution failed",
                new RuntimeException("HTTP 401 Unauthorized"));

        assertThat(LlmErrorMessages.classify(wrapped)).contains("API key");
    }

    /**
     * 兜底分支要短且固定，避免几百字符的 SDK 异常灌进 SSE 和对话历史；
     * 而且<b>不许替用户判病因</b>——调用方的 catch 也罩着落历史、写记忆、checkpoint 落库，
     * 数据库挂了同样走这条路，兜底若说"请检查端点与模型配置"，用户会去乱改一把没问题的 key
     */
    @Test
    void 未知错误既不回显原文也不替用户判病因() {
        String longMsg = "x".repeat(500);

        String msg = LlmErrorMessages.classify(new IllegalStateException(longMsg));

        assertThat(msg).doesNotContain("xxxx").doesNotContain("配置");
    }

    /**
     * 任何分支都不许把 key 透出去。正则永远追不全 key 的形态（URL 里的 ?api_key=、
     * 自定义 header、非 sk- 前缀的自建 key……），所以兜底根本不回显原文——
     * 这段文本不只给用户看，专家失败时还会拼进 AssistantMessage 喂回模型并进 checkpoint 持久化
     */
    @Test
    void 任何情况下不回显key() {
        assertThat(LlmErrorMessages.classify(new RuntimeException(
                "request failed, Authorization: Bearer sk-secret-abcdef123456")))
                .doesNotContain("sk-secret-abcdef123456");
        // 正则追不到的形态也必须安全
        assertThat(LlmErrorMessages.classify(new RuntimeException(
                "GET https://gw.example.com/v1/chat?api_key=Zm9vYmFyMTIzNDU2 failed")))
                .doesNotContain("Zm9vYmFyMTIzNDU2");
    }
}
