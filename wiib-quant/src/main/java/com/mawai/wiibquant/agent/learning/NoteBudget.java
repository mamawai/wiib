package com.mawai.wiibquant.agent.learning;

import com.mawai.wiibcommon.enums.AgentLang;

/**
 * 笔记（复盘记忆 / 学习笔记）的总量上限，按语言取：中文 2000 字、英文 4000 字符
 * —— 同等信息量下英文字符数约为中文的三倍。
 * <p>
 * 提示词里那句"≤N"用这个值注入（{@code reviewer.system} / {@code learning.system} 的
 * {@code maxChars} 占位符）：截断值与提示词必须同源。
 */
final class NoteBudget {

    private NoteBudget() {
    }

    /** 复盘记忆与学习笔记同口径：两处都会整份覆盖写，字数账要一样才不会一处松一处紧 */
    static int maxChars(AgentLang lang) {
        return lang == AgentLang.EN ? 4000 : 2000;
    }
}
