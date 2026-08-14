package com.mawai.wiibquant.task;

import com.mawai.wiibquant.agent.runtime.AiAgentRuntime;
import com.mawai.wiibquant.agent.runtime.AiAgentRuntimeManager;
import com.mawai.wiibquant.market.domain.news.NewsFlash;
import com.mawai.wiibquant.market.service.NewsCache;
import com.mawai.wiibquant.mapper.NewsEventMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 快讯采集轨：定时拉 BlockBeats 重要快讯 → 增量去重 → 轻模型批量打标 → 落 news_event。
 * 给 K 线新闻图标供数，顺带为将来的事件研究攒数据。
 * <p>
 * 走 {@link NewsCache} 而不是直连客户端：BlockBeats 免费额度一次性不回血，
 * 缓存 10 分钟窗口内与对话/深研判共享同一次拉取，采集不额外多烧一份额度。
 * <p>
 * 失败语义全部"跳过本轮，下轮自愈"：快讯在拉取窗口里能活一阵（重要快讯每天几十条、
 * 窗口 20 条），下轮重试大概率还在；打标失败硬插空标反而让这批永远失去打标机会（去重键挡住重入）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NewsEventCollector {

    /**
     * BlockBeats create_time 是"2026-07-09 00:30:12"格式的墙钟时间，按北京时间解析成 epoch。
     * 这是推断不是实测（BlockBeats 中文服务、cn 快讯源，几乎必然如此）；上服务器后拿
     * 最新一条快讯的落库时间对一下当下时刻即可证实，偏差恒定 8 小时就是这里错了。
     */
    private static final ZoneId SOURCE_ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter SOURCE_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final NewsCache newsCache;
    private final NewsTagger tagger;
    private final NewsEventMapper newsEventMapper;
    private final AiAgentRuntimeManager runtimeManager;

    @Value("${news.collect.enabled:true}")
    private boolean enabled;
    /** 封闭词表：OIL/GOLD/BTC + 美股白名单，改配置即改打标空间，历史行不回溯 */
    @Value("${news.collect.vocabulary:OIL,GOLD,BTC,COIN,MSTR,TSLA,NVDA}")
    private List<String> vocabulary;

    /** 周期见 news.collect.interval-ms。initialDelay 让开启动高峰，也给 Admin 配模型留缓冲 */
    @Scheduled(fixedDelayString = "${news.collect.interval-ms:1800000}", initialDelay = 60_000)
    public void collect() {
        if (!enabled) {
            return;
        }
        List<NewsFlash> flashes = newsCache.getFlashes();
        if (flashes.isEmpty()) {
            return;
        }
        List<NewsFlash> fresh = onlyNew(flashes);
        if (fresh.isEmpty()) {
            return;
        }
        AiAgentRuntime runtime;
        try {
            runtime = runtimeManager.current();
        } catch (IllegalStateException e) {
            // 未配置功能位：不落库（落了就再没有打标机会），配好下轮自动开始
            log.warn("[NewsCollect] news-tagging 功能位未配置，本轮跳过（新快讯 {} 条待收）", fresh.size());
            return;
        }
        Map<Long, String> tags;
        try {
            tags = tagger.tag(runtime.newsTaggingChatModel(), fresh, vocabulary);
        } catch (Exception e) {
            log.warn("[NewsCollect] 打标失败跳过本轮，下轮重试（新快讯 {} 条）: {}", fresh.size(), e.toString());
            return;
        }
        int inserted = 0;
        int tagged = 0;
        for (NewsFlash f : fresh) {
            String tag = tags.getOrDefault(f.id(), "");
            inserted += newsEventMapper.insertIgnore(f.id(), f.title(), f.plainContent(), f.url(),
                    publishedAtMs(f), tag, runtime.newsTaggingModelName());
            if (!tag.isEmpty()) {
                tagged++;
            }
        }
        log.info("[NewsCollect] 收 {} 条（有标 {} 条）", inserted, tagged);
    }

    private List<NewsFlash> onlyNew(List<NewsFlash> flashes) {
        Set<Long> existing = new HashSet<>(newsEventMapper.selectExistingSourceIds(
                flashes.stream().map(NewsFlash::id).toList()));
        return flashes.stream().filter(f -> !existing.contains(f.id())).toList();
    }

    /** 解析失败退当前时刻：图标位置差一点仍可用，比整条丢掉强 */
    private static long publishedAtMs(NewsFlash flash) {
        try {
            return LocalDateTime.parse(flash.createTime(), SOURCE_TIME)
                    .atZone(SOURCE_ZONE).toInstant().toEpochMilli();
        } catch (Exception e) {
            log.warn("[NewsCollect] create_time 解析失败 id={} raw={}", flash.id(), flash.createTime());
            return System.currentTimeMillis();
        }
    }
}
