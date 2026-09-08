package com.mawai.wiibagent.i18n;

import static org.assertj.core.api.Assertions.assertThat;

/** 双语契约的共用断言："英文侧一个中文字都不许有"要在多个包里各钉一遍，码点区间只留这一份 */
public final class PromptI18nAssertions {

    private PromptI18nAssertions() {
    }

    /** 逐码点扫 CJK：汉字、全角标点、中文书名号/引号一个都不许漏进英文提示词 */
    public static void assertNoCjk(String label, String text) {
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            boolean cjk = (cp >= 0x4E00 && cp <= 0x9FFF)      // 基本汉字
                    || (cp >= 0x3400 && cp <= 0x4DBF)          // 扩展A
                    || (cp >= 0x3000 && cp <= 0x303F)          // 中日韩符号（【】、。等）
                    || (cp >= 0xFF00 && cp <= 0xFFEF);         // 全角形式（％｜（）等）
            assertThat(cjk)
                    .as("%s 里混进了中日韩字符 U+%04X（%s）", label, cp, new String(Character.toChars(cp)))
                    .isFalse();
        }
    }
}
