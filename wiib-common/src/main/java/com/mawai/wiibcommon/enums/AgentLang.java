package com.mawai.wiibcommon.enums;

import java.util.Locale;
import java.util.Optional;

/**
 * AI 产出语言：系统提示词、工具描述、模型回答一律跟它走——全中文就全中文，全英文就全英文。
 * <p>
 * 语言码与前端 i18n 的 Lang 逐字相同（zh/en），落库在 {@code user.lang} 列。
 * 与界面语言（前端 localStorage）是两个开关：建号时取当时的界面语言当初值，之后只在配置页改。
 * <p>
 * 【改名警告】code() 的字符串就是 user.lang 列里存的值，也是 prompts/&lt;code&gt;/ 词表目录名，
 * 上线后只准加新的，不准改名。
 */
public enum AgentLang {

    ZH("zh"),
    EN("en");

    private final String code;

    AgentLang(String code) {
        this.code = code;
    }

    /** 语言码，与前端 Lang、user.lang 列、prompts 词表目录名三处同一套字符串 */
    public String code() {
        return code;
    }

    /**
     * 精确匹配（忽略大小写与首尾空白），认不出返回 empty。
     * 校验前端传进来的值、按词表目录名认语言用它——这两处认不出就该报错，不该悄悄变中文。
     */
    public static Optional<AgentLang> find(String code) {
        if (code == null) {
            return Optional.empty();
        }
        String normalized = code.trim().toLowerCase(Locale.ROOT);
        for (AgentLang lang : values()) {
            if (lang.code.equals(normalized)) {
                return Optional.of(lang);
            }
        }
        return Optional.empty();
    }

    /** 认不出、为空一律回落 ZH——存量用户 lang 是 NULL，回落中文即维持现状 */
    public static AgentLang of(String code) {
        return find(code).orElse(ZH);
    }
}
