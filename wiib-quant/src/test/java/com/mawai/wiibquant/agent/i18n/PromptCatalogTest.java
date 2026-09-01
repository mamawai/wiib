package com.mawai.wiibquant.agent.i18n;

import com.mawai.wiibcommon.enums.AgentLang;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 生产提示词词表的装配验收。引擎行为（装配规则、缺 key、占位符、撞 key）归
 * wiib-common 的 {@code LangBundleTest}，这里只验 prompts/ 那份真词表装得起来、真有双语。
 */
class PromptCatalogTest {

    /** 生产那份 prompts/ 得装得起来，behavior 现役 key 两门语言都真有自己的文案 */
    @Test
    void 生产词表两门语言都装得起来() {
        PromptCatalog production = new PromptCatalog();

        for (AgentLang lang : AgentLang.values()) {
            assertThat(production.get(lang, "behavior.system", Map.of("schema", "<SCHEMA>")))
                    .contains("<SCHEMA>");
            assertThat(production.get(lang, "behavior.intro", Map.of("userId", 7L))).contains("7");
        }
        // 英文缺 key 会静默回落中文，看 get 有没有返回是查不出漏翻译的：两边一字不差才是没翻
        for (String key : List.of("behavior.system", "behavior.intro",
                "behavior.section.user-profile", "behavior.section.futures-stats")) {
            assertThat(production.find(AgentLang.EN, key))
                    .as("英文词表缺 %s，回落成中文了", key)
                    .isNotEqualTo(production.find(AgentLang.ZH, key));
        }
    }
}
