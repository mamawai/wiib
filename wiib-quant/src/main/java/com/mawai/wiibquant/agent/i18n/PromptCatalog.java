package com.mawai.wiibquant.agent.i18n;

import com.mawai.wiibcommon.enums.AgentLang;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 提示词词表：一门语言一个 {@code resources/prompts/<语言码>.yml}，启动时全读进内存
 * （提示词是热路径，每次唤醒都要用，不每次读盘）。
 * <p>
 * <b>自动装配</b>：扫 {@code prompts/*.yml}，文件名即语言码——加一门语言 = 加一个 yml + 加一个
 * {@link AgentLang} 常量，这个类一行不动（照前端 i18n 的 import.meta.glob 思路）。
 * 文件名认不出对应枚举就在启动期炸，不静默丢一整门语言。
 * <p>
 * <b>key 约定</b>：点分层级，与前端 {@code ns.key} 一个心智——
 * {@code <域>.<用途>}（behavior.system / trader.systemTemplate）、工具描述固定
 * {@code tool.<工具名>}。长文本用 YAML 的 {@code |} 块标量，换行原样保留。
 * <p>
 * <b>缺 key</b>：不回空串（模型会收到残缺提示词却看不出来）。非中文缺就回落中文并记 WARN
 * （翻译没跟上，功能不能停）；中文也缺是编码错误，当场抛。占位符没给值同理。
 */
@Slf4j
@Component
public class PromptCatalog {

    /** 回落语言：谁缺 key 都回落到它；它自己缺就没得落了，所以必须存在 */
    private static final AgentLang FALLBACK = AgentLang.ZH;

    /** {{name}} 占位符，与前端 i18next 同款写法；两边留白容忍 */
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{\\s*([\\w.]+)\\s*}}");

    private final Map<AgentLang, Map<String, String>> byLang;

    public PromptCatalog() {
        // classpath*: 与 mybatis mapper-locations 同款——打成 Boot fat jar 后照样枚举得到目录里的条目
        this("classpath*:prompts/*.yml");
    }

    /** 单测用：换个目录装一套词表，不碰生产那份 */
    public PromptCatalog(String locationPattern) {
        this.byLang = load(locationPattern);
    }

    /** 取一条提示词。缺 key 见类注释——只会回落或抛，不会给空串 */
    public String get(AgentLang lang, String key) {
        return get(lang, key, Map.of());
    }

    /** 取一条提示词并填 {{占位符}}。少给一个值就抛——提示词里留着 {{xxx}} 发给模型是纯事故 */
    public String get(AgentLang lang, String key, Map<String, Object> vars) {
        String template = find(lang, key);
        if (template == null) {
            throw new IllegalStateException(
                    "提示词缺 key [" + key + "]：" + FALLBACK.code() + ".yml 是回落源，这条必须有");
        }
        return render(key, template, vars);
    }

    /**
     * 取不到返回 null，不抛。只给「缺了有正当回落」的调用方用——目前只有
     * {@link LocalizedToolCallbacks}：还没搬进 yml 的工具要保留注解里的原描述照常工作。
     * 提示词正文一律走 {@link #get}。
     */
    public String find(AgentLang lang, String key) {
        String text = byLang.getOrDefault(lang, Map.of()).get(key);
        if (text != null) {
            return text;
        }
        String fallback = byLang.get(FALLBACK).get(key);
        if (fallback != null && lang != FALLBACK) {
            log.warn("提示词 {}.yml 缺 key [{}]，本次回落 {}", lang.code(), key, FALLBACK.code());
        }
        return fallback;
    }

    private static String render(String key, String template, Map<String, Object> vars) {
        Matcher matcher = PLACEHOLDER.matcher(template);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String name = matcher.group(1);
            Object value = vars.get(name);
            if (value == null) {
                throw new IllegalStateException("提示词 [" + key + "] 的占位符 {{" + name + "}} 没给值");
            }
            matcher.appendReplacement(out, Matcher.quoteReplacement(value.toString()));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    private static Map<AgentLang, Map<String, String>> load(String locationPattern) {
        Resource[] files;
        try {
            files = new PathMatchingResourcePatternResolver().getResources(locationPattern);
        } catch (IOException e) {
            throw new IllegalStateException("提示词目录读不了：" + locationPattern, e);
        }

        Map<AgentLang, Map<String, String>> result = new EnumMap<>(AgentLang.class);
        for (Resource file : files) {
            String fileName = Objects.requireNonNull(file.getFilename());
            String code = fileName.substring(0, fileName.lastIndexOf('.'));
            AgentLang lang = AgentLang.find(code).orElseThrow(() -> new IllegalStateException(
                    "提示词文件 " + fileName + " 没有对应的 AgentLang 常量：加语言要连枚举一起加"));
            result.put(lang, flatten(file));
            log.info("提示词装配 {}：{} 条", fileName, result.get(lang).size());
        }

        if (!result.containsKey(FALLBACK)) {
            throw new IllegalStateException(
                    locationPattern + " 里缺 " + FALLBACK.code() + ".yml：它是所有语言的回落源");
        }
        return Map.copyOf(result);
    }

    /** YAML 的嵌套层级压成点分 key（trader: systemTemplate: → trader.systemTemplate），与前端 ns.key 对齐 */
    private static Map<String, String> flatten(Resource file) {
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(file);
        Properties flat = Objects.requireNonNull(yaml.getObject(), () -> "提示词解析为空：" + file.getFilename());
        Map<String, String> texts = new HashMap<>();
        flat.forEach((key, value) -> texts.put(key.toString(), value.toString()));
        return Map.copyOf(texts);
    }
}
