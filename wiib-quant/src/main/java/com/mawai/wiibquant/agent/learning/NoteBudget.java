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

    /**
     * 预算内截断：超限时退到预算内最后一个完整句读收笔（中文句读直接算；半角 .!? 须后跟空白，
     * 免得切在 2463.39 这类小数点上）。段标题行不含句读，回退时天然连「只剩标题没正文」的尾段一起收掉。
     * 句读太靠前（不足预算一半）或全程无句读时硬截断兜底；硬截断不劈代理对。
     */
    static String clip(String text, int maxChars) {
        if (text.length() <= maxChars) {
            return text;
        }
        for (int i = maxChars - 1; i >= maxChars / 2; i--) {
            char c = text.charAt(i);
            boolean fullWidth = c == '。' || c == '！' || c == '？' || c == '；';
            boolean halfWidth = (c == '.' || c == '!' || c == '?' || c == ';')
                    && Character.isWhitespace(text.charAt(i + 1));
            if (fullWidth || halfWidth) {
                return text.substring(0, i + 1);
            }
        }
        int cut = Character.isHighSurrogate(text.charAt(maxChars - 1)) ? maxChars - 1 : maxChars;
        return text.substring(0, cut);
    }
}
