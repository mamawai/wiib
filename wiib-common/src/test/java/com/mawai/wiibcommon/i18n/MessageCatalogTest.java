package com.mawai.wiibcommon.i18n;

import com.mawai.wiibcommon.enums.AgentLang;
import com.mawai.wiibcommon.enums.ErrorCode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 错误码文案的两门语言。
 * <p>
 * 要钉死的是<b>每个 ErrorCode 都有两门语言的话</b>：漏一条不会报错，只会在英文界面上
 * 悄悄冒出一行中文——这正是这轮要根治的病。
 */
class MessageCatalogTest {

    private final MessageCatalog messages = new MessageCatalog();

    @AfterEach
    void clearLang() {
        RequestLang.clear();
    }

    @Test
    void 每个错误码两门语言都有话且不是回落() {
        for (ErrorCode code : ErrorCode.values()) {
            assertThat(messages.get(AgentLang.ZH, code.getMsgKey()))
                    .as("%s 缺中文", code.name())
                    .isNotBlank();
            assertThat(messages.get(AgentLang.EN, code.getMsgKey()))
                    .as("%s 的英文缺失，回落成中文了", code.name())
                    .isNotBlank()
                    .isNotEqualTo(messages.get(AgentLang.ZH, code.getMsgKey()));
        }
    }

    @Test
    void 英文词表里不该有中文() {
        for (ErrorCode code : ErrorCode.values()) {
            assertThat(messages.get(AgentLang.EN, code.getMsgKey()))
                    .as("%s 的英文里混着中文", code.name())
                    .doesNotMatch("(?s).*[\\u4e00-\\u9fff].*");
        }
    }

    @Test
    void 不带语言时跟当次请求的界面语言走() {
        RequestLang.set(AgentLang.EN);
        assertThat(messages.get(ErrorCode.BALANCE_NOT_ENOUGH.getMsgKey())).isEqualTo("Not enough balance");

        RequestLang.set(AgentLang.ZH);
        assertThat(messages.get(ErrorCode.BALANCE_NOT_ENOUGH.getMsgKey())).isEqualTo("余额不足");
    }

    /** 非 HTTP 线程（定时任务、异步虚拟线程）没设过语言：回落中文即维持存量行为 */
    @Test
    void 没设过语言回落中文() {
        RequestLang.clear();
        assertThat(messages.get(ErrorCode.BALANCE_NOT_ENOUGH.getMsgKey())).isEqualTo("余额不足");
    }

    /** 占位符没给值就抛：留着 {{name}} 发到界面上是纯事故 */
    @Test
    void 占位符没给值当场抛() {
        assertThatThrownBy(() -> messages.get(AgentLang.ZH, "error.request.missingParam"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("{{name}}");

        assertThat(messages.get(AgentLang.EN, "error.request.missingParam",
                Map.of("name", "symbol"))).isEqualTo("Missing parameter: symbol");
    }
}
