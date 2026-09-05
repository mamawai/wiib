package com.mawai.wiibquant.market.service;

import com.mawai.wiibcommon.enums.AgentLang;
import com.mawai.wiibquant.market.domain.news.NewsFlash;
import com.mawai.wiibquant.mapper.NewsEventMapper;
import com.mawai.wiibquant.mapper.NewsEventMapper.Translation;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 快讯取字：源是 BlockBeats 中文快讯，译文在打标时同批产出、落 news_event，
 * 这里按 source_id 把译文对上；<b>缺译文回落中文原文</b>（存量老快讯、刚拉到还没进采集轨的、
 * 模型没译成的都走这条）。
 * <p>
 * 两种取法：{@link #localize} 按 agent 语言取一份，给对话 news 专家、深研判这些模型侧用；
 * {@link #bilingual} 中英一起给，首页快讯卡按界面语言现选，切语言不用再打接口。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NewsFlashLocalizer {

    private final NewsEventMapper newsEventMapper;

    /**
     * 取过语言的一条快讯。
     *
     * @param translated true=标题或正文用的是机器译文，false=中文原文
     */
    public record LocalizedFlash(long id, String title, String plain, String url,
                                 String createTime, boolean translated) {
    }

    /** 中英两套都带的一条快讯：titleEn/plainEn 空=没译成，前端回落中文 */
    public record BilingualFlash(long id, String title, String plain, String titleEn, String plainEn,
                                 String url, String createTime) {
    }

    /** 按语言取一份快讯。译文列只有英文一份，其余语言一律原文，中文一次库都不查 */
    public List<LocalizedFlash> localize(List<NewsFlash> flashes, AgentLang lang) {
        if (lang != AgentLang.EN || flashes.isEmpty()) {
            return flashes.stream().map(NewsFlashLocalizer::original).toList();
        }
        Map<Long, Translation> byId = translations(flashes);
        return flashes.stream().map(f -> {
            Translation t = byId.get(f.id());
            String titleEn = t == null ? null : t.getTitleEn();
            String contentEn = t == null ? null : t.getContentEn();
            // 标题/正文各自回落：只译成一半的那种也算译文，前端照样打标
            return new LocalizedFlash(f.id(), pick(titleEn, f.title()),
                    pick(contentEn, f.plainContent()), f.url(), f.createTime(),
                    has(titleEn) || has(contentEn));
        }).toList();
    }

    /** 中英两套一起给 */
    public List<BilingualFlash> bilingual(List<NewsFlash> flashes) {
        Map<Long, Translation> byId = flashes.isEmpty() ? Map.of() : translations(flashes);
        return flashes.stream().map(f -> {
            Translation t = byId.get(f.id());
            return new BilingualFlash(f.id(), f.title(), f.plainContent(),
                    t == null ? null : t.getTitleEn(), t == null ? null : t.getContentEn(),
                    f.url(), f.createTime());
        }).toList();
    }

    /** 按 source_id 查译文；查失败给空表，快讯整块退回原文总好过消失 */
    private Map<Long, Translation> translations(List<NewsFlash> flashes) {
        Map<Long, Translation> byId = new HashMap<>();
        try {
            newsEventMapper.selectTranslations(flashes.stream().map(NewsFlash::id).toList())
                    .forEach(t -> byId.put(t.getSourceId(), t));
        } catch (Exception e) {
            log.warn("[NewsI18n] 译文查询失败，本次回落原文: {}", e.toString());
        }
        return byId;
    }

    private static boolean has(String translation) {
        return translation != null && !translation.isBlank();
    }

    private static LocalizedFlash original(NewsFlash f) {
        return new LocalizedFlash(f.id(), f.title(), f.plainContent(), f.url(), f.createTime(), false);
    }

    /** 译文空着就是没译成，回落原文 */
    private static String pick(String translation, String origin) {
        return has(translation) ? translation : origin;
    }
}
