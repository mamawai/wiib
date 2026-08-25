package com.mawai.wiibcommon.i18n;

import com.mawai.wiibcommon.enums.AgentLang;
import org.junit.jupiter.api.Test;

import java.util.Map;
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
 * 走 {@code classpath*:}，扫的是<b>本模块测试类路径上能看见的全部</b>域文件。
 * quant / sim 各有各的域文件，各自的测试里扫各自那份（依赖 wiib-common，common 的也一并覆盖）。
 */
class MessageParityTest {

    /** {{name}} 占位符，与 LangBundle 同一套写法 */
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{\\s*([\\w.]+)\\s*}}");

    static final LangBundle MESSAGES = new LangBundle("界面文案", "classpath*:messages/*/*.yml");

    /** 两门语言各自缺了对方哪些 key */
    static void assertParity(LangBundle bundle) {
        Map<String, String> zh = bundle.texts(AgentLang.ZH);
        Map<String, String> en = bundle.texts(AgentLang.EN);
        assertThat(zh).as("中文词表一条都没扫到").isNotEmpty();

        Set<String> missingEn = new TreeSet<>(zh.keySet());
        missingEn.removeAll(en.keySet());
        Set<String> missingZh = new TreeSet<>(en.keySet());
        missingZh.removeAll(zh.keySet());
        assertThat(missingEn).as("英文词表缺这些 key，界面上会回落成中文").isEmpty();
        assertThat(missingZh).as("中文词表缺这些 key").isEmpty();
    }

    /** 英文侧不该混进中文 */
    static void assertEnglishHasNoCjk(LangBundle bundle) {
        bundle.texts(AgentLang.EN).forEach((k, v) ->
                assertThat(v).as("%s 的英文里混着中文", k)
                        .doesNotMatch("(?s).*[\\u4e00-\\u9fff].*"));
    }

    /** 占位符也要对齐：一边写了 {{name}} 另一边漏了，缺值那侧渲染时会当场抛 */
    static void assertPlaceholdersMatch(LangBundle bundle) {
        Map<String, String> en = bundle.texts(AgentLang.EN);
        bundle.texts(AgentLang.ZH).forEach((k, zhText) ->
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

    @Test
    void 两门语言key完全对齐() {
        assertParity(MESSAGES);
    }

    @Test
    void 英文词表没有中文() {
        assertEnglishHasNoCjk(MESSAGES);
    }

    @Test
    void 两门语言的占位符一一对应() {
        assertPlaceholdersMatch(MESSAGES);
    }
}
