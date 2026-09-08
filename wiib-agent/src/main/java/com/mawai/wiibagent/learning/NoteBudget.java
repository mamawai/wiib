package com.mawai.wiibagent.learning;

import com.mawai.wiibcommon.enums.AgentLang;

/**
 * 笔记（复盘记忆 / 学习笔记）的篇幅预算，按语言取：中文 2000 字、英文 4000 字符
 * —— 同等信息量下英文字符数约为中文的三倍。
 * <p>
 * 只作为提示词里那句"≤N"的取值（{@code reviewer.system} / {@code learning.system} 的
 * {@code maxChars} 占位符），落库不按它裁：篇幅由模型自己收着写，超出一点照存。
 * 笔记是覆盖写且每轮唤醒整段注入，这个数管的是往模型里塞多少，不是存不存得下。
 */
final class NoteBudget {

    private NoteBudget() {
    }

    /** 复盘记忆与学习笔记同口径：两处都会整份覆盖写，字数账要一样才不会一处松一处紧 */
    static int maxChars(AgentLang lang) {
        return lang == AgentLang.EN ? 4000 : 2000;
    }
}
