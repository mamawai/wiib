package com.mawai.wiibcommon.i18n;

import com.mawai.wiibcommon.enums.AgentLang;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 界面文案两门语言的对齐。
 * <p>
 * 缺一条 key 不会报错——{@link LangBundle} 会回落中文并记一条 WARN，日志里没人看，
 * 界面上就是英文里冒出一行中文。这个类把"回落"变成构建期的红。
 * <p>
 * 直接扫 yml 文件对齐，不经 LangBundle——引擎不为对齐检查开测试专用口；
 * 装配行为归 {@code LangBundleTest}。目录=语言、文件=域，与引擎同一套约定。
 * <p>
 * 走 {@code classpath*:}，扫的是<b>本模块测试类路径上能看见的全部</b>域文件。
 * quant / sim 各有各的域文件，各自的测试里扫各自那份（依赖 wiib-common，common 的也一并覆盖）。
 */
class MessageParityTest {

    /** {{name}} 占位符，与 LangBundle 同一套写法 */
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{\\s*([\\w.]+)\\s*}}");

    private static final Map<AgentLang, Map<String, String>> MESSAGES = load("classpath*:messages/*/*.yml");

    /** 按引擎同款约定装 yml：父目录名=语言码，嵌套层级压成点分 key */
    static Map<AgentLang, Map<String, String>> load(String pattern) {
        Map<AgentLang, Map<String, String>> byLang = new EnumMap<>(AgentLang.class);
        try {
            for (Resource file : new PathMatchingResourcePatternResolver().getResources(pattern)) {
                String[] parts = file.getURL().getPath().split("/");
                AgentLang lang = AgentLang.find(parts[parts.length - 2]).orElseThrow();
                YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
                yaml.setResources(file);
                Objects.requireNonNull(yaml.getObject()).forEach((k, v) ->
                        byLang.computeIfAbsent(lang, x -> new HashMap<>()).put(k.toString(), v.toString()));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return byLang;
    }

    private static Map<String, String> texts(AgentLang lang) {
        return MESSAGES.getOrDefault(lang, Map.of());
    }

    @Test
    void 两门语言key完全对齐() {
        Map<String, String> zh = texts(AgentLang.ZH);
        Map<String, String> en = texts(AgentLang.EN);
        assertThat(zh).as("中文词表一条都没扫到").isNotEmpty();

        Set<String> missingEn = new TreeSet<>(zh.keySet());
        missingEn.removeAll(en.keySet());
        Set<String> missingZh = new TreeSet<>(en.keySet());
        missingZh.removeAll(zh.keySet());
        assertThat(missingEn).as("英文词表缺这些 key，界面上会回落成中文").isEmpty();
        assertThat(missingZh).as("中文词表缺这些 key").isEmpty();
    }

    @Test
    void 英文词表没有中文() {
        texts(AgentLang.EN).forEach((k, v) ->
                assertThat(v).as("%s 的英文里混着中文", k)
                        .doesNotMatch("(?s).*[\\u4e00-\\u9fff].*"));
    }

    /** 占位符也要对齐：一边写了 {{name}} 另一边漏了，缺值那侧渲染时会当场抛 */
    @Test
    void 两门语言的占位符一一对应() {
        Map<String, String> en = texts(AgentLang.EN);
        texts(AgentLang.ZH).forEach((k, zhText) ->
                assertThat(placeholders(en.getOrDefault(k, "")))
                        .as("%s 的占位符两门语言对不上", k)
                        .isEqualTo(placeholders(zhText)));
    }

    private static Set<String> placeholders(String text) {
        Set<String> out = new TreeSet<>();
        Matcher m = PLACEHOLDER.matcher(text);
        while (m.find()) {
            out.add(m.group(1));
        }
        return out;
    }
}
