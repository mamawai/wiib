package com.mawai.wiibcommon.enums;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 两种查法故意分开：读 user.lang 用 of（认不出回落中文，存量 NULL 就靠它），
 * 校验前端传值、按 yml 文件名认语言用 find（认不出就该报错，不能悄悄变中文）。
 */
class AgentLangTest {

    @Test
    void 语言码与前端和库里存的字符串一致() {
        assertThat(AgentLang.ZH.code()).isEqualTo("zh");
        assertThat(AgentLang.EN.code()).isEqualTo("en");
    }

    @Test
    void of认得出的照旧_大小写与空白容忍() {
        assertThat(AgentLang.of("zh")).isEqualTo(AgentLang.ZH);
        assertThat(AgentLang.of("en")).isEqualTo(AgentLang.EN);
        assertThat(AgentLang.of(" EN ")).isEqualTo(AgentLang.EN);
    }

    @Test
    void of认不出的一律回落中文() {
        // 存量用户 lang 是 NULL，回落中文即维持现状
        assertThat(AgentLang.of(null)).isEqualTo(AgentLang.ZH);
        assertThat(AgentLang.of("")).isEqualTo(AgentLang.ZH);
        assertThat(AgentLang.of("fr")).isEqualTo(AgentLang.ZH);
        // 带地区码不认：全仓只按语言级铺一份词表，前端也只送 zh/en
        assertThat(AgentLang.of("zh-CN")).isEqualTo(AgentLang.ZH);
        // sim 挂了时 SimInternalClient 返回的是错误 JSON，一样得落到中文而不是抛
        assertThat(AgentLang.of("{\"error\":\"boom\"}")).isEqualTo(AgentLang.ZH);
    }

    @Test
    void find只认精确匹配() {
        assertThat(AgentLang.find("en")).contains(AgentLang.EN);
        assertThat(AgentLang.find("ja")).isEmpty();
        assertThat(AgentLang.find(null)).isEmpty();
        assertThat(AgentLang.find("zh-CN")).isEmpty();
    }
}
