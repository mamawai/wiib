package com.mawai.wiibquant.agent.i18n;

import com.mawai.wiibcommon.enums.AgentLang;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 词表的四条契约：自动装配、占位符、缺 key 三种行为、装不起来就在启动期炸。
 * <p>最值钱的是"缺 key 不给空串"——空串会让模型收到一份残缺提示词还照样作答，
 * 从产出里根本看不出来是提示词掉了。
 * <p>目录=语言、文件=域：catalog-test 下 zh/en 各两个域文件，合起来才是一门语言的全表。
 */
class PromptCatalogTest {

    private final PromptCatalog catalog = new PromptCatalog("classpath*:catalog-test/*/*.yml");

    @Test
    void 目录里的yml自动装配_加语言不用改代码() {
        // catalog-test 下放了 zh/en 两个目录，谁都没在代码里登记过
        assertThat(catalog.get(AgentLang.ZH, "demo.plain")).isEqualTo("你好");
        assertThat(catalog.get(AgentLang.EN, "demo.plain")).isEqualTo("hello");
    }

    /** 一门语言的多个域文件压进同一张表：demo 与 tool 各写各的，取词时看不出分过文件 */
    @Test
    void 同一语言的多个域文件合成一张表() {
        assertThat(catalog.get(AgentLang.ZH, "demo.plain")).isEqualTo("你好");
        assertThat(catalog.get(AgentLang.ZH, "tool.echo_tool")).isEqualTo("回声工具（词表中文描述）");
    }

    /** 两个域文件撞同一条 key：静默留一条丢一条＝模型收到另一个域的文案，必须启动期炸 */
    @Test
    void 跨域文件撞key_装配期就炸() {
        assertThatThrownBy(() -> new PromptCatalog("classpath*:catalog-dupkey/*/*.yml"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("demo.plain");
    }

    @Test
    void 块标量原样保留换行() {
        assertThat(catalog.get(AgentLang.ZH, "demo.greet", Map.of("name", "阿伟")))
                .isEqualTo("你好 阿伟，\n欢迎回来\n");
    }

    @Test
    void 占位符按名替换() {
        assertThat(catalog.get(AgentLang.EN, "demo.greet", Map.of("name", "Ada")))
                .contains("Hi Ada,");
    }

    @Test
    void 英文缺key_回落中文而不是空串() {
        assertThat(catalog.get(AgentLang.EN, "demo.onlyZh")).isEqualTo("只有中文词表有这条");
    }

    @Test
    void 中文也缺这条key_当场抛() {
        assertThatThrownBy(() -> catalog.get(AgentLang.ZH, "demo.nobody"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("demo.nobody");
        assertThatThrownBy(() -> catalog.get(AgentLang.EN, "demo.nobody"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 占位符没给值_当场抛而不是把大括号发给模型() {
        assertThatThrownBy(() -> catalog.get(AgentLang.ZH, "demo.greet"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("{{name}}");
    }

    @Test
    void 语言目录没有对应枚举_装配期就炸() {
        assertThatThrownBy(() -> new PromptCatalog("classpath*:catalog-badlang/*/*.yml"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ja");
    }

    @Test
    void 缺回落语言的词表_装配期就炸() {
        assertThatThrownBy(() -> new PromptCatalog("classpath*:catalog-nozh/*/*.yml"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("zh");
    }

    /** 生产那份 prompts/ 也得装得起来，behavior 现役 key 两门语言都真有自己的文案 */
    @Test
    void 生产词表两门语言都装得起来() {
        PromptCatalog production = new PromptCatalog();

        for (AgentLang lang : AgentLang.values()) {
            assertThat(production.get(lang, "behavior.system", Map.of("schema", "<SCHEMA>")))
                    .contains("<SCHEMA>");
            assertThat(production.get(lang, "behavior.intro", Map.of("userId", 7L))).contains("7");
        }
        // 英文缺 key 会静默回落中文，看 get 有没有返回是查不出漏翻译的：两边一字不差才是没翻
        for (String key : List.of("behavior.system", "behavior.intro",
                "behavior.section.user-profile", "behavior.section.futures-stats")) {
            assertThat(production.find(AgentLang.EN, key))
                    .as("英文词表缺 %s，回落成中文了", key)
                    .isNotEqualTo(production.find(AgentLang.ZH, key));
        }
    }
}
