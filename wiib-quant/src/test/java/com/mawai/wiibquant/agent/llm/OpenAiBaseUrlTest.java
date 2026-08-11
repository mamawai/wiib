package com.mawai.wiibquant.agent.llm;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 官方 SDK 要 baseUrl 自带 /v1，本项目对用户的约定是不带——这个转换就是两套约定之间的接缝。
 * 漏了它的后果是「检测模型」打到 {baseUrl}/models 拿 404，而同一份配置跑对话却好好的
 * （对话走 ResponsesChatModel，那条路自己拼 /v1/responses，不经 SDK）。
 */
class OpenAiBaseUrlTest {

    /** 用户按表单提示填的形状（不含 /v1），补上之后 SDK 才找得到 /v1/models */
    @Test
    void 不带v1的补上() {
        assertThat(OpenAiBaseUrl.forSdk("https://codex.example.com"))
                .isEqualTo("https://codex.example.com/v1");
    }

    /** 用户自己带了后缀就不能再补——拼成 /v1/v1 一样是 404，只是换个地方错 */
    @Test
    void 已带v1的原样返回() {
        assertThat(OpenAiBaseUrl.forSdk("https://codex.example.com/v1"))
                .isEqualTo("https://codex.example.com/v1");
    }

    /** 尾斜杠要先去掉再判断，否则 ".../v1/" 会被当成没带 v1，补成 ".../v1//v1" */
    @Test
    void 尾斜杠不影响判断() {
        assertThat(OpenAiBaseUrl.forSdk("https://codex.example.com/")).isEqualTo("https://codex.example.com/v1");
        assertThat(OpenAiBaseUrl.forSdk("https://codex.example.com/v1/")).isEqualTo("https://codex.example.com/v1");
    }

    /** 空值原样交回：baseUrl 该不该为空由上游校验说了算，这里不越权替它决定 */
    @Test
    void 空值不炸() {
        assertThat(OpenAiBaseUrl.forSdk(null)).isNull();
        assertThat(OpenAiBaseUrl.forSdk("  ")).isEqualTo("  ");
    }
}
