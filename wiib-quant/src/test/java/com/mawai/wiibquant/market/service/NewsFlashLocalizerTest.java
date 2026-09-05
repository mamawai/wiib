package com.mawai.wiibquant.market.service;

import com.mawai.wiibcommon.enums.AgentLang;
import com.mawai.wiibquant.market.domain.news.NewsFlash;
import com.mawai.wiibquant.market.service.NewsFlashLocalizer.BilingualFlash;
import com.mawai.wiibquant.market.service.NewsFlashLocalizer.LocalizedFlash;
import com.mawai.wiibquant.mapper.NewsEventMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 取用侧按语言取字段。两条底线：
 * <ul>
 *   <li>缺译文回落中文原文——译文列留空是"没译成"的唯一表示，回落必须是原文而不是空白</li>
 *   <li>中文一次库都不查——译文只有英文一份，中文用户不该为此多付一次查询</li>
 * </ul>
 */
class NewsFlashLocalizerTest {

    private final NewsEventMapper mapper = mock(NewsEventMapper.class);
    private final NewsFlashLocalizer localizer = new NewsFlashLocalizer(mapper);

    private static NewsFlash flash(long id, String title, String content) {
        return new NewsFlash(id, title, content, "https://x/" + id, "2026-08-12 14:03:00");
    }

    private static NewsEventMapper.Translation translation(long sourceId, String title, String content) {
        NewsEventMapper.Translation t = new NewsEventMapper.Translation();
        t.setSourceId(sourceId);
        t.setTitleEn(title);
        t.setContentEn(content);
        return t;
    }

    @Test
    void 中文原样返回且不查库() {
        List<LocalizedFlash> out = localizer.localize(
                List.of(flash(1, "美联储维持利率", "<p>据 CME 数据</p>")), AgentLang.ZH);

        assertThat(out).singleElement().satisfies(f -> {
            assertThat(f.title()).isEqualTo("美联储维持利率");
            assertThat(f.plain()).isEqualTo("据 CME 数据");   // 仍然脱 HTML
            assertThat(f.translated()).isFalse();
        });
        verify(mapper, never()).selectTranslations(anyList());
    }

    @Test
    void 英文有译文就换字并标为译文() {
        when(mapper.selectTranslations(List.of(1L)))
                .thenReturn(List.of(translation(1L, "Fed holds rates", "Per CME data")));

        List<LocalizedFlash> out = localizer.localize(
                List.of(flash(1, "美联储维持利率", "<p>据 CME 数据</p>")), AgentLang.EN);

        assertThat(out).singleElement().satisfies(f -> {
            assertThat(f.title()).isEqualTo("Fed holds rates");
            assertThat(f.plain()).isEqualTo("Per CME data");
            assertThat(f.translated()).isTrue();
        });
    }

    @Test
    void 英文缺译文回落中文原文() {
        // 存量老快讯（库里根本没这行）与模型没译成（列是 NULL/空串）走同一条回落
        when(mapper.selectTranslations(List.of(1L, 2L)))
                .thenReturn(List.of(translation(2L, null, "  ")));

        List<LocalizedFlash> out = localizer.localize(
                List.of(flash(1, "旧快讯", "<p>旧正文</p>"), flash(2, "没译成", "<p>正文</p>")), AgentLang.EN);

        assertThat(out).extracting(LocalizedFlash::title).containsExactly("旧快讯", "没译成");
        assertThat(out).extracting(LocalizedFlash::plain).containsExactly("旧正文", "正文");
        assertThat(out).allSatisfy(f -> assertThat(f.translated()).isFalse());
    }

    @Test
    void 只译成一半时另一半仍回落原文() {
        when(mapper.selectTranslations(List.of(1L)))
                .thenReturn(List.of(translation(1L, "Fed holds rates", null)));

        LocalizedFlash f = localizer.localize(
                List.of(flash(1, "美联储维持利率", "<p>据 CME 数据</p>")), AgentLang.EN).getFirst();

        assertThat(f.title()).isEqualTo("Fed holds rates");
        assertThat(f.plain()).isEqualTo("据 CME 数据");
        assertThat(f.translated()).isTrue();
    }

    @Test
    void 译文查询挂了整块回落原文而不是丢空() {
        when(mapper.selectTranslations(anyList())).thenThrow(new RuntimeException("库挂了"));

        LocalizedFlash f = localizer.localize(
                List.of(flash(1, "美联储维持利率", "<p>据 CME 数据</p>")), AgentLang.EN).getFirst();

        assertThat(f.title()).isEqualTo("美联储维持利率");
        assertThat(f.translated()).isFalse();
    }

    @Test
    void 双语取法中英一起给且缺译文留空() {
        when(mapper.selectTranslations(List.of(1L, 2L)))
                .thenReturn(List.of(translation(1L, "Fed holds rates", "Per CME data")));

        List<BilingualFlash> out = localizer.bilingual(
                List.of(flash(1, "美联储维持利率", "<p>据 CME 数据</p>"), flash(2, "旧快讯", "<p>旧正文</p>")));

        assertThat(out).extracting(BilingualFlash::title).containsExactly("美联储维持利率", "旧快讯");
        assertThat(out).extracting(BilingualFlash::plain).containsExactly("据 CME 数据", "旧正文");
        assertThat(out).extracting(BilingualFlash::titleEn).containsExactly("Fed holds rates", null);
        assertThat(out).extracting(BilingualFlash::plainEn).containsExactly("Per CME data", null);
    }
}
