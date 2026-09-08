package com.mawai.wiibcommon.i18n;

import com.mawai.wiibcommon.enums.AgentLang;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 词表引擎的四条契约：自动装配、占位符、缺 key 三种行为、装不起来就在启动期炸。
 * <p>最值钱的是"缺 key 不给空串"——空串会让模型收到一份残缺提示词还照样作答，
 * 从产出里根本看不出来是提示词掉了。
 * <p>目录=语言、文件=域：catalog-test 下 zh/en 各两个域文件，合起来才是一门语言的全表。
 * 两份生产词表（提示词/界面文案）各自的装配验收在各自模块的测试里，这里只测引擎。
 */
class LangBundleTest {

    private final LangBundle bundle = new LangBundle("测试词表", "classpath*:catalog-test/*/*.yml");

    @Test
    void 目录里的yml自动装配_加语言不用改代码() {
        // catalog-test 下放了 zh/en 两个目录，谁都没在代码里登记过
        assertThat(bundle.get(AgentLang.ZH, "demo.plain")).isEqualTo("你好");
        assertThat(bundle.get(AgentLang.EN, "demo.plain")).isEqualTo("hello");
    }

    /** 一门语言的多个域文件压进同一张表：demo 与 tool 各写各的，取词时看不出分过文件 */
    @Test
    void 同一语言的多个域文件合成一张表() {
        assertThat(bundle.get(AgentLang.ZH, "demo.plain")).isEqualTo("你好");
        assertThat(bundle.get(AgentLang.ZH, "tool.echo_tool")).isEqualTo("回声工具（词表中文描述）");
    }

    /** 两个域文件撞同一条 key：静默留一条丢一条＝上线拿到另一个域的文案，必须启动期炸 */
    @Test
    void 跨域文件撞key_装配期就炸() {
        assertThatThrownBy(() -> new LangBundle("测试词表", "classpath*:catalog-dupkey/*/*.yml"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("demo.plain");
    }

    @Test
    void 块标量原样保留换行() {
        assertThat(bundle.get(AgentLang.ZH, "demo.greet", Map.of("name", "阿伟")))
                .isEqualTo("你好 阿伟，\n欢迎回来\n");
    }

    @Test
    void 占位符按名替换() {
        assertThat(bundle.get(AgentLang.EN, "demo.greet", Map.of("name", "Ada")))
                .contains("Hi Ada,");
    }

    @Test
    void 英文缺key_回落中文而不是空串() {
        assertThat(bundle.get(AgentLang.EN, "demo.onlyZh")).isEqualTo("只有中文词表有这条");
    }

    @Test
    void 中文也缺这条key_当场抛() {
        assertThatThrownBy(() -> bundle.get(AgentLang.ZH, "demo.nobody"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("demo.nobody");
        assertThatThrownBy(() -> bundle.get(AgentLang.EN, "demo.nobody"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 占位符没给值_当场抛而不是把大括号发出去() {
        assertThatThrownBy(() -> bundle.get(AgentLang.ZH, "demo.greet"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("{{name}}");
    }

    @Test
    void 语言目录没有对应枚举_装配期就炸() {
        assertThatThrownBy(() -> new LangBundle("测试词表", "classpath*:catalog-badlang/*/*.yml"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ja");
    }

    @Test
    void 缺回落语言的词表_装配期就炸() {
        assertThatThrownBy(() -> new LangBundle("测试词表", "classpath*:catalog-nozh/*/*.yml"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("zh");
    }
}
