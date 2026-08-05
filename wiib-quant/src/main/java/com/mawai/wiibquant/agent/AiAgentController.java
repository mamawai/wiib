package com.mawai.wiibquant.agent;

import cn.dev33.satoken.stp.StpUtil;
import com.mawai.wiibcommon.annotation.CurrentUserId;
import com.mawai.wiibcommon.annotation.Symbol;
import com.mawai.wiibcommon.util.Result;
import com.mawai.wiibquant.agent.behavior.BehaviorAnalysisReport;
import com.mawai.wiibquant.agent.behavior.BehaviorAnalysisService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.mawai.wiibquant.agent.toolkit.NewsCache;
import com.mawai.wiibcommon.entity.QuantDeepAnalysis;
import com.mawai.wiibquant.mapper.QuantDeepAnalysisMapper;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * AI Agent 查询接口：behavior 分析 + 深研判 + 快讯（P7 研判工作台数据源）。
 * 预测端点（snapshots/scorecard/series）已随预测管线下线（2026-08：生产验证无前瞻信息），
 * 对话入口在 {@link com.mawai.wiibquant.agent.chat.ChatWorkbenchController}。
 */
@Slf4j
@Tag(name = "AI Agent接口")
@RestController
@RequestMapping("/api/ai")
@RequiredArgsConstructor
public class AiAgentController {

    private final BehaviorAnalysisService behaviorAnalysisService;
    private final QuantDeepAnalysisMapper deepAnalysisMapper;
    private final NewsCache newsCache;

    @PostMapping("/analyze-behavior")
    @Operation(summary = "用户行为分析")
    public Result<BehaviorAnalysisReport> analyzeBehavior(@CurrentUserId long userId) {
        return behaviorAnalysisService.analyze(userId);
    }

    @GetMapping("/quant/analysis/latest")
    @Operation(summary = "查最新深研判（研判叙事/情景分布/失效条件/无方向态）")
    public Result<QuantDeepAnalysis> latestAnalysis(@Symbol String symbol) {
        StpUtil.checkLogin();
        QuantDeepAnalysis analysis = deepAnalysisMapper.selectOne(new LambdaQueryWrapper<QuantDeepAnalysis>()
                .eq(QuantDeepAnalysis::getSymbol, symbol)
                .orderByDesc(QuantDeepAnalysis::getCloseTime)
                .last("LIMIT 1"));
        return analysis != null ? Result.ok(analysis) : Result.fail("暂无深研判数据");
    }

    @GetMapping("/quant/analysis/list")
    @Operation(summary = "深研判历史列表（时间线标记+详情回看）")
    public Result<List<QuantDeepAnalysis>> analysisList(
            @Symbol String symbol,
            @RequestParam(defaultValue = "20") int limit) {
        StpUtil.checkLogin();
        return Result.ok(deepAnalysisMapper.selectList(new LambdaQueryWrapper<QuantDeepAnalysis>()
                .eq(QuantDeepAnalysis::getSymbol, symbol)
                .orderByDesc(QuantDeepAnalysis::getCloseTime)
                .last("LIMIT " + Math.clamp(limit, 1, 100))));
    }

    /** 快讯条目：正文脱 HTML 的纯文本，前端直接展示 */
    public record NewsFlashView(long id, String title, String plain, String url, String createTime) {
    }

    @GetMapping("/quant/news")
    @Operation(summary = "重要快讯（BlockBeats 内存缓存：未过期复用不打上游，首页快讯卡数据源）")
    public Result<List<NewsFlashView>> news() {
        StpUtil.checkLogin();
        return Result.ok(newsCache.getFlashes().stream()
                .map(f -> new NewsFlashView(f.id(), f.title(), f.plainContent(), f.url(), f.createTime()))
                .toList());
    }
}
