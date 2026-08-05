package com.mawai.wiibquant.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.mawai.wiibcommon.annotation.CurrentUserId;
import com.mawai.wiibcommon.dto.FuturesOrderResponse;
import com.mawai.wiibcommon.dto.FuturesPositionDTO;
import com.mawai.wiibcommon.entity.AiTrader;
import com.mawai.wiibcommon.entity.AiTraderDecision;
import com.mawai.wiibcommon.util.Result;
import com.mawai.wiibquant.agent.strategy.execution.SimTradeClient;
import com.mawai.wiibquant.agent.trader.TraderService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * AI Trader：我的 trader 管理（创建/配置/启停/重置）+ 公开竞技场（排行/详情/决策时间线/净值曲线）。
 * 竞技场读接口登录即可看（决策日志天生公开——观赏性是产品核心）；写操作只动自己的 trader。
 */
@Slf4j
@Tag(name = "AI Trader")
@RestController
@RequestMapping("/api/ai/trader")
@RequiredArgsConstructor
public class TraderController {

    private final TraderService traderService;
    private final SimTradeClient simTradeClient;

    // ========== 我的 trader ==========

    /** 公开视图：任何登录用户可见的字段。key/baseUrl/自定义提示词绝不进公开视图。 */
    public record TraderPublicView(long id, String name, String model, String status, String pausedReason,
                                   String symbols, String intervalCode, int roundNo,
                                   BigDecimal equity, BigDecimal pnlPct, boolean mine) {
    }

    /** 主人视图：公开视图 + 配置回显（key 只回尾4位）。 */
    public record TraderOwnerView(TraderPublicView pub, String apiProtocol, String baseUrl,
                                  String customPrompt, String apiKeyTail) {
    }

    @GetMapping("/mine")
    @Operation(summary = "我的trader（含配置回显，key只回尾4位）")
    public Result<TraderOwnerView> mine(@CurrentUserId long userId) {
        AiTrader t = traderService.mine(userId);
        if (t == null) {
            return Result.ok(null);
        }
        return Result.ok(new TraderOwnerView(publicView(t, userId),
                t.getApiProtocol(), t.getBaseUrl(), t.getCustomPrompt(), traderService.keyTail(t)));
    }

    public record UpsertRequest(String name, String symbols, String intervalCode, String customPrompt,
                                String apiProtocol, String baseUrl, String model, String apiKey) {
        TraderService.UpsertReq toReq() {
            return new TraderService.UpsertReq(name, symbols, intervalCode, customPrompt,
                    apiProtocol, baseUrl, model, apiKey);
        }
    }

    @PostMapping
    @Operation(summary = "创建trader（连通性校验→key加密→开sim子账户注资10000）")
    public Result<Void> create(@CurrentUserId long userId, @RequestBody UpsertRequest req) {
        String err = traderService.create(userId, req.toReq());
        return err == null ? Result.ok(null) : Result.fail(err);
    }

    @PutMapping("/config")
    @Operation(summary = "改配置（apiKey传空=不换；提示词改完下一根K线生效）")
    public Result<Void> updateConfig(@CurrentUserId long userId, @RequestBody UpsertRequest req) {
        String err = traderService.updateConfig(userId, req.toReq());
        return err == null ? Result.ok(null) : Result.fail(err);
    }

    @PostMapping("/start")
    @Operation(summary = "启动")
    public Result<Void> start(@CurrentUserId long userId) {
        String err = traderService.start(userId);
        return err == null ? Result.ok(null) : Result.fail(err);
    }

    @PostMapping("/pause")
    @Operation(summary = "暂停")
    public Result<Void> pause(@CurrentUserId long userId) {
        String err = traderService.pause(userId);
        return err == null ? Result.ok(null) : Result.fail(err);
    }

    @PostMapping("/reset")
    @Operation(summary = "重置开新局（round+1，新子账户注资10000，历史留档）")
    public Result<Void> reset(@CurrentUserId long userId) {
        String err = traderService.reset(userId);
        return err == null ? Result.ok(null) : Result.fail(err);
    }

    // ========== 公开竞技场 ==========

    @GetMapping("/arena")
    @Operation(summary = "竞技场列表（全部trader按收益率排序）")
    public Result<List<TraderPublicView>> arena() {
        long viewer = StpUtil.getLoginIdAsLong();
        return Result.ok(traderService.all().stream()
                .map(t -> publicView(t, viewer))
                .sorted((a, b) -> b.pnlPct().compareTo(a.pnlPct()))
                .toList());
    }

    public record TraderDetailView(TraderPublicView trader,
                                   List<FuturesPositionDTO> positions,
                                   List<FuturesOrderResponse> pendingOrders) {
    }

    @GetMapping("/{id}")
    @Operation(summary = "trader详情（当前持仓与挂单实时现查）")
    public Result<TraderDetailView> detail(@PathVariable long id) {
        long viewer = StpUtil.getLoginIdAsLong();
        AiTrader t = traderService.byId(id);
        if (t == null) {
            return Result.fail("trader不存在");
        }
        List<FuturesPositionDTO> positions = List.of();
        List<FuturesOrderResponse> pending = List.of();
        try {
            positions = simTradeClient.getAllPositions(t.getSimUserId());
            pending = simTradeClient.getPendingOrders(t.getSimUserId(), null);
        } catch (Exception e) {
            log.warn("[Trader] 详情持仓查询失败 traderId={} msg={}", id, e.getMessage());
        }
        return Result.ok(new TraderDetailView(publicView(t, viewer), positions, pending));
    }

    @GetMapping("/{id}/decisions")
    @Operation(summary = "决策时间线（倒序分页，before传上一页最旧wakeTime）")
    public Result<List<AiTraderDecision>> decisions(@PathVariable long id,
                                                    @RequestParam(defaultValue = "50") int limit,
                                                    @RequestParam(required = false) Long before) {
        StpUtil.checkLogin();
        return Result.ok(traderService.decisions(id, limit, before));
    }

    public record EquityPoint(long wakeTime, BigDecimal equity) {
    }

    @GetMapping("/{id}/equity-curve")
    @Operation(summary = "净值曲线（round缺省=当前局）")
    public Result<List<EquityPoint>> equityCurve(@PathVariable long id,
                                                 @RequestParam(required = false) Integer round) {
        StpUtil.checkLogin();
        AiTrader t = traderService.byId(id);
        if (t == null) {
            return Result.fail("trader不存在");
        }
        return Result.ok(traderService.equityCurve(t, round).stream()
                .map(d -> new EquityPoint(d.getWakeTime(), d.getEquity()))
                .toList());
    }

    private TraderPublicView publicView(AiTrader t, long viewerUserId) {
        BigDecimal equity = traderService.latestEquity(t);
        BigDecimal pnlPct = equity.subtract(TraderService.INITIAL_BALANCE)
                .divide(TraderService.INITIAL_BALANCE, 4, RoundingMode.HALF_UP)
                .multiply(new BigDecimal("100"));
        return new TraderPublicView(t.getId(), t.getName(), t.getModel(), t.getStatus(), t.getPausedReason(),
                t.getSymbols(), t.getIntervalCode(), t.getRoundNo(),
                equity.setScale(2, RoundingMode.HALF_UP), pnlPct.setScale(2, RoundingMode.HALF_UP),
                t.getUserId() == viewerUserId);
    }
}
