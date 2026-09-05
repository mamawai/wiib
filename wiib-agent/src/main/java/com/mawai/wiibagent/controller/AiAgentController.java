package com.mawai.wiibagent.controller;

import com.mawai.wiibcommon.dto.NewsEventItem;
import com.mawai.wiibcommon.util.Result;
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

    private final NewsEventMapper newsEventMapper;

    /** 首页快讯卡最多给 100 条，约两三天的量 */
    private static final int NEWS_LIMIT = 100;

    /**
     * 首页快讯卡读 news_event 存档，不走模型侧那份 20 条的内存缓存。
     * 中英两套一起给，前端按界面语言现选；译文空=没译成，英文界面不展示那条。
     */
    @GetMapping("/quant/news")
    @Operation(summary = "最新快讯（news_event 存档，首页快讯卡数据源）")
    public Result<List<NewsEventItem>> news() {
        return Result.ok(newsEventMapper.selectLatest(NEWS_LIMIT));
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
