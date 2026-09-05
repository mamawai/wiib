package com.mawai.wiibagent.controller;

import com.mawai.wiibcommon.dto.NewsEventItem;
import com.mawai.wiibcommon.util.Result;
import com.mawai.wiibquant.market.service.NewsCache;
import com.mawai.wiibquant.market.service.NewsFlashLocalizer;
import com.mawai.wiibquant.mapper.NewsEventMapper;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * AI Agent 查询接口：现只剩快讯两条。
 * 深研判查询端点已随 AI 页市场研判 tab 下线（2026-08）：研判只在对话里触发时看，
 * 生成与落库仍在 DeepAnalysisToolkit/DeepAnalysisService。
 * 行为分析端点同理下线（2026-08）：它已是对话轨的 analyze_my_behavior 工具，
 * 报告以卡片形式出现在对话里，生成与准入在 BehaviorToolkit/BehaviorAnalysisService。
 * 预测端点（snapshots/scorecard/series）已随预测管线下线（2026-08：生产验证无前瞻信息），
 * 对话入口在 {@link com.mawai.wiibagent.chat.ChatWorkbenchController}。
 */
@Slf4j
@Tag(name = "AI Agent接口")
@RestController
@RequestMapping("/api/ai")
@RequiredArgsConstructor
public class AiAgentController {

    private final NewsCache newsCache;
    private final NewsFlashLocalizer newsFlashLocalizer;
    private final NewsEventMapper newsEventMapper;

    /** 快讯中英两套一起给，前端按界面语言现选；译文空=没译成，前端回落中文 */
    @GetMapping("/quant/news")
    @Operation(summary = "重要快讯（BlockBeats 内存缓存：未过期复用不打上游，首页快讯卡数据源）")
    public Result<List<NewsFlashLocalizer.BilingualFlash>> news() {
        return Result.ok(newsFlashLocalizer.bilingual(newsCache.getFlashes()));
    }

    @GetMapping("/quant/news-events")
    @Operation(summary = "打标快讯（K线新闻图标数据源：按标签+时间窗查 news_event 存档）")
    public Result<List<NewsEventItem>> newsEvents(@RequestParam String tag,
                                                  @RequestParam long from,
                                                  @RequestParam long to) {
        // 上限 500：图标按 K 线桶聚合，一屏至多几百桶，多给纯属流量浪费
        return Result.ok(newsEventMapper.selectByTagInRange(tag.trim().toUpperCase(), from, to, 500));
    }
}
