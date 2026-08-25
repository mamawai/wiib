package com.mawai.wiibquant.agent.toolkit;

import com.mawai.wiibcommon.enums.AgentLang;
import com.mawai.wiibquant.market.domain.news.NewsFlash;
import com.mawai.wiibquant.market.service.NewsCache;
import com.mawai.wiibquant.market.service.NewsFlashLocalizer;
import com.mawai.wiibquant.mapper.NewsEventMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NewsToolkitTest {

    private final NewsCache newsCache = mock(NewsCache.class);
    private final NewsEventMapper newsEventMapper = mock(NewsEventMapper.class);
    private final NewsToolkit toolkit =
            new NewsToolkit(newsCache, new NewsFlashLocalizer(newsEventMapper));

    private static NewsEventMapper.Translation translation(long sourceId, String title, String content) {
        NewsEventMapper.Translation t = new NewsEventMapper.Translation();
        t.setSourceId(sourceId);
        t.setTitleEn(title);
        t.setContentEn(content);
        return t;
    }

    @Test
    void newsSearchReturnsFlashesFromCacheStrippingHtml() {
        when(newsCache.getFlashes()).thenReturn(List.of(
                new NewsFlash(1L, "美联储维持利率", "<p>据 CME 数据…</p>", "https://x", "2026-07-09 00:30:12")));

        String json = toolkit.newsSearch(AgentLang.ZH);

        assertThat(json).contains("美联储维持利率").contains("据 CME 数据");
        assertThat(json).doesNotContain("<p>"); // HTML 标签已去除
        verify(newsEventMapper, never()).selectTranslations(anyList());   // 中文一次库都不查
    }

    @Test
    void 英文取译文缺译文的那条回落中文原文() {
        when(newsCache.getFlashes()).thenReturn(List.of(
                new NewsFlash(1L, "美联储维持利率", "<p>据 CME 数据…</p>", "https://x", "2026-07-09 00:30:12"),
                new NewsFlash(2L, "某所遭黑客攻击", "<p>损失约 1 亿美元</p>", "https://y", "2026-07-09 00:31:12")));
        when(newsEventMapper.selectTranslations(List.of(1L, 2L))).thenReturn(List.of(
                translation(1L, "Fed holds rates", "Per CME data...")));

        String json = toolkit.newsSearch(AgentLang.EN);

        assertThat(json).contains("Fed holds rates").contains("Per CME data")
                .doesNotContain("美联储维持利率");
        // 2 号没有译文行：回落中文原文，绝不拿原文冒充译文
        assertThat(json).contains("某所遭黑客攻击").contains("损失约 1 亿美元");
    }

    @Test
    void newsSearchDegradesWhenCacheEmpty() {
        when(newsCache.getFlashes()).thenReturn(List.of());

        assertThat(toolkit.newsSearch(AgentLang.ZH)).contains("\"available\":false");
    }
}
