package com.mawai.wiibcommon.i18n;

import com.mawai.wiibcommon.enums.AgentLang;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.io.IOException;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 语言词表引擎：{@code <根>/<语言码>/<域>.yml}，一门语言一个目录、一个域一个文件，
 * 构造时全读进内存（词表是热路径，不每次读盘）。
 * <p>
 * <b>目录=语言、文件=域</b>：与前端 {@code locales/<lng>/<ns>.json} 完全同构。分文件是为了
 * 各域各写各的、不抢同一个文件；加一门语言 = 加一个目录 + 加一个 {@link AgentLang} 常量，
 * 这个类一行不动。目录名认不出对应枚举就在启动期炸，不静默丢一整门语言。
 * <p>
 * <b>key 约定</b>：点分层级，与前端 {@code ns.key} 一个心智。<b>域前缀写在 yml 内部</b>
 * （trader.yml 里第一层就是 {@code trader:}），分了文件也不去掉——同一门语言的所有文件压进
 * 同一张表，去掉前缀就会跨文件撞 key。长文本用 YAML 的 {@code |} 块标量，换行原样保留。
 * <p>
 * <b>缺 key</b>：不回空串（拿到残缺文案却看不出来）。非中文缺就回落中文并记 WARN
 * （翻译没跟上，功能不能停）；中文也缺是编码错误，当场抛。占位符没给值同理。
 * <p>
 * 两份词表共用它：{@code prompts/} 是喂给模型的提示词，{@code messages/} 是给用户看的界面文案。
 * {@code what} 只出现在日志与报错里，用来分清是哪一份出的问题。
 */
@Slf4j
public final class LangBundle {

    /** 回落语言：谁缺 key 都回落到它；它自己缺就没得落了，所以必须存在 */
    private static final AgentLang FALLBACK = AgentLang.ZH;

    /** {{name}} 占位符，与前端 i18next 同款写法；两边留白容忍 */
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{\\s*([\\w.]+)\\s*}}");

    /** 这份词表的用途名（"提示词"/"界面文案"），只用于日志与报错 */
    private final String what;
    private final Map<AgentLang, Map<String, String>> byLang;

    public LangBundle(String what, String locationPattern) {
        this.what = what;
        this.byLang = load(what, locationPattern);
    }

    /** 取一条。缺 key 见类注释——只会回落或抛，不会给空串 */
    public String get(AgentLang lang, String key) {
        return get(lang, key, Map.of());
    }

    /** 取一条并填 {{占位符}}。少给一个值就抛——留着 {{xxx}} 发出去是纯事故 */
    public String get(AgentLang lang, String key, Map<String, Object> vars) {
        String template = find(lang, key);
        if (template == null) {
            throw new IllegalStateException(
                    what + "缺 key [" + key + "]：" + FALLBACK.code() + " 是回落语言，这条必须有");
        }
        return render(what, key, template, vars);
    }

    /**
     * 某门语言实际装到的那一份（不回落，装配后不可变）。给对齐检查用：
     * 缺 key 只会回落 + 记 WARN，日志里没人看，界面上就是英文里冒出一行中文，
     * 得有个地方能把"回落"看成红。
     */
    public Map<String, String> texts(AgentLang lang) {
        return byLang.getOrDefault(lang, Map.of());
    }

    /**
     * 取不到返回 null，不抛。只给「缺了有正当回落」的调用方用；正文一律走 {@link #get}。
     * <p>lang 为 null 视同回落语言，与 {@link AgentLang#of} 对"认不出"的处置一致——
     * 查词表不该因为语言没给就炸。
     */
    public String find(AgentLang lang, String key) {
        if (lang == null) {
            lang = FALLBACK;
        }
        String text = byLang.getOrDefault(lang, Map.of()).get(key);
        if (text != null) {
            return text;
        }
        String fallback = byLang.get(FALLBACK).get(key);
        if (fallback != null && lang != FALLBACK) {
            log.warn("{} {} 词表缺 key [{}]，本次回落 {}", what, lang.code(), key, FALLBACK.code());
        }
        return fallback;
    }

    private static String render(String what, String key, String template, Map<String, Object> vars) {
        Matcher matcher = PLACEHOLDER.matcher(template);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String name = matcher.group(1);
            Object value = vars.get(name);
            if (value == null) {
                throw new IllegalStateException(what + " [" + key + "] 的占位符 {{" + name + "}} 没给值");
            }
            matcher.appendReplacement(out, Matcher.quoteReplacement(value.toString()));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    private static Map<AgentLang, Map<String, String>> load(String what, String locationPattern) {
        Resource[] files;
        try {
            files = new PathMatchingResourcePatternResolver().getResources(locationPattern);
        } catch (IOException e) {
            throw new IllegalStateException(what + "目录读不了：" + locationPattern, e);
        }

        Map<AgentLang, Map<String, String>> result = new EnumMap<>(AgentLang.class);
        for (Resource file : files) {
            String code = langDir(what, file);
            AgentLang lang = AgentLang.find(code).orElseThrow(() -> new IllegalStateException(
                    what + "语言目录 " + code + " 没有对应的 AgentLang 常量：加语言要连枚举一起加"));
            Map<String, String> domain = flatten(what, file);
            merge(what, result.computeIfAbsent(lang, k -> new HashMap<>()), domain, file, code);
            log.info("{}装配 {}/{}：{} 条", what, code, file.getFilename(), domain.size());
        }

        if (!result.containsKey(FALLBACK)) {
            throw new IllegalStateException(
                    locationPattern + " 里缺 " + FALLBACK.code() + " 目录：它是所有语言的回落源");
        }
        Map<AgentLang, Map<String, String>> frozen = new EnumMap<>(AgentLang.class);
        result.forEach((lang, texts) -> frozen.put(lang, Map.copyOf(texts)));
        return frozen;
    }

    /** 语言码取自父目录名（prompts/zh/trader.yml → zh）；jar 里的 URL 同样是斜杠分段，一套解析走到底 */
    private static String langDir(String what, Resource file) {
        String path;
        try {
            path = file.getURL().getPath();
        } catch (IOException e) {
            throw new IllegalStateException(what + "文件定位不了：" + file, e);
        }
        String[] parts = path.split("/");
        if (parts.length < 2) {
            throw new IllegalStateException(what + "文件不在语言目录下：" + path);
        }
        return parts[parts.length - 2];
    }

    /**
     * 同一门语言的多个域文件压进同一张表。撞 key 当场炸：两个文件写了同一条，
     * 静默留一条丢一条＝上线后拿到的是另一个域的文案，从结果里看不出来。
     */
    private static void merge(String what, Map<String, String> texts, Map<String, String> domain,
                              Resource file, String code) {
        domain.forEach((key, value) -> {
            String old = texts.putIfAbsent(key, value);
            if (old != null) {
                throw new IllegalStateException(what + " key [" + key + "] 在 " + code
                        + " 的多个域文件里重复定义（本次来自 " + file.getFilename() + "）：域前缀要与文件名对齐");
            }
        });
    }

    /** YAML 的嵌套层级压成点分 key（trader: systemTemplate: → trader.systemTemplate），与前端 ns.key 对齐 */
    private static Map<String, String> flatten(String what, Resource file) {
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(file);
        Properties flat = Objects.requireNonNull(yaml.getObject(), () -> what + "解析为空：" + file.getFilename());
        Map<String, String> texts = new HashMap<>();
        flat.forEach((key, value) -> texts.put(key.toString(), value.toString()));
        return Map.copyOf(texts);
    }
}
