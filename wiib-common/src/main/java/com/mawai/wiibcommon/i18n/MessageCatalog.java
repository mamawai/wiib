package com.mawai.wiibcommon.i18n;

import com.mawai.wiibcommon.enums.AgentLang;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 界面文案词表：{@code resources/messages/<语言码>/<域>.yml}，装配与回落规则见 {@link LangBundle}。
 * <p>
 * 装的是<b>给用户看</b>的话——报错、校验提示、动作回执。语言跟当次请求的 {@link RequestLang}，
 * 与提示词词表（跟 trader 主人的 user.lang）是两个来源，别混。
 * <p>
 * <b>各模块各写各的域文件</b>：{@code classpath*:} across jar 合并，sim 放 auth/sim，
 * quant 放 trader/admin，common 放 error。撞 key 装配期就炸。
 */
@Component
public class MessageCatalog {

    private final LangBundle bundle;

    public MessageCatalog() {
        this("classpath*:messages/*/*.yml");
    }

    /** 单测用：换个目录装一套词表，不碰生产那份 */
    public MessageCatalog(String locationPattern) {
        this.bundle = new LangBundle("界面文案", locationPattern);
    }

    /** 按当次请求的界面语言取一条 */
    public String get(String key) {
        return get(RequestLang.current(), key);
    }

    /** 按当次请求的界面语言取一条并填 {{占位符}} */
    public String get(String key, Map<String, Object> vars) {
        return get(RequestLang.current(), key, vars);
    }

    /** 指定语言取一条。给不在请求线程上的调用方与单测用 */
    public String get(AgentLang lang, String key) {
        return bundle.get(lang, key);
    }

    public String get(AgentLang lang, String key, Map<String, Object> vars) {
        return bundle.get(lang, key, vars);
    }
}
