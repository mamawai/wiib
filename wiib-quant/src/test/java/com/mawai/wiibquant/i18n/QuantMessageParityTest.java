package com.mawai.wiibquant.i18n;

import com.mawai.wiibcommon.enums.AgentLang;
import com.mawai.wiibcommon.i18n.LangBundle;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * quant 侧界面文案两门语言的对齐（连带覆盖 wiib-common 那份，它在同一条类路径上）。
 * <p>
 * 缺 key 只会回落中文 + 一条没人看的 WARN，界面上就是英文里冒出一行中文——这里让它变成红。
 * 与 {@code PromptI18nTest} 分工：那边管喂给模型的提示词，这边管给用户看的话。
 */
class QuantMessageParityTest {

    private static final LangBundle MESSAGES = new LangBundle("界面文案", "classpath*:messages/*/*.yml");

    @Test
    void 两门语言key完全对齐() {
        Map<String, String> zh = MESSAGES.texts(AgentLang.ZH);
        Map<String, String> en = MESSAGES.texts(AgentLang.EN);
        assertThat(zh).isNotEmpty();
        Set<String> missingEn = new TreeSet<>(zh.keySet());
        missingEn.removeAll(en.keySet());
        Set<String> missingZh = new TreeSet<>(en.keySet());
        missingZh.removeAll(zh.keySet());
        assertThat(missingEn).as("英文词表缺这些 key，界面上会回落成中文").isEmpty();
        assertThat(missingZh).as("中文词表缺这些 key").isEmpty();
    }

    @Test
    void 英文词表没有中文() {
        MESSAGES.texts(AgentLang.EN).forEach((k, v) ->
                assertThat(v).as("%s 的英文里混着中文", k)
                        .doesNotMatch("(?s).*[\u4e00-\u9fff].*"));
    }

    /** quant 的域文件都装上了：漏放一个 messages 目录，上面两条会因为"没这些 key"而假绿 */
    @Test
    void quant自己的域文件确实装上了() {
        assertThat(MESSAGES.texts(AgentLang.ZH)).containsKeys(
                "trader.notCreated", "trader.wake.paused", "quant.backtest.rangeInvalid",
                "quant.chat.sessionNotFound", "quant.admin.adminOnly");
    }
}
