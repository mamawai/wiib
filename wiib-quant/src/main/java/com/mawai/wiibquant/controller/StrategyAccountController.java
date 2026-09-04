package com.mawai.wiibquant.controller;

import com.mawai.wiibcommon.annotation.RequireAdmin;
import com.mawai.wiibcommon.exception.BizException;
import com.mawai.wiibcommon.util.Result;
import com.mawai.wiibcommon.i18n.MessageCatalog;
import com.mawai.wiibquant.strategy.core.StrategyRuntime;
import com.mawai.wiibquant.strategy.core.StrategySignalState;
import com.mawai.wiibquant.strategy.monitor.StrategyAccountService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 三策略模拟盘账户监控 + 受控平仓。
 *
 * <p>前缀挂 /api/ai 下复用既有网关分流（nginx 零改动）。查看对所有登录用户开放；
 * 平仓是干预性写操作，仅 userId=1（平台所有者）可执行——前端同步只对 id=1 渲染按钮，
 * 此处校验是真正的门，前端隐藏只是体验。</p>
 */
@Slf4j
@Tag(name = "策略账户监控")
@RestController
@RequestMapping("/api/ai/strategies")
@RequiredArgsConstructor
public class StrategyAccountController {

    private final StrategyAccountService strategyAccountService;
    private final StrategyRuntime strategyRuntime;
    /** 管理页的提示跟界面语言 */
    private final MessageCatalog messages;

    @Data
    public static class ClosePositionRequest {
        private Long positionId;
    }

    @GetMapping("/overview")
    @Operation(summary = "三策略账户全景（余额/权益/盈亏/持仓/已平仓历史）")
    public Result<List<StrategyAccountService.StrategyAccountView>> overview() {
        return Result.ok(strategyAccountService.overview());
    }

    @GetMapping("/signals")
    @Operation(summary = "各策略×币种实时信号状态快照（通道位置/压缩计数/签名命中等）")
    public Result<List<StrategySignalState>> signals() {
        return Result.ok(strategyRuntime.signalStates());
    }

    @PostMapping("/{strategyId}/close")
    @Operation(summary = "手动整仓市价平（仅 userId=1）")
    @RequireAdmin // 平仓是干预性写操作，仅管理员可执行
    public Result<Void> close(@PathVariable String strategyId, @RequestBody ClosePositionRequest request) {
        if (request == null || request.getPositionId() == null) {
            return Result.fail(messages.get("quant.strategy.positionIdRequired"));
        }
        try {
            strategyAccountService.closePosition(strategyId, request.getPositionId());
            return Result.ok(null);
        } catch (BizException e) {
            throw e;   // 已成文的业务拒因（仓位不存在），交全局处理器原样下发，别再包一层"平仓失败"
        } catch (Exception e) {
            log.error("[StrategyAccount] 平仓失败 strategyId={} posId={}", strategyId, request.getPositionId(), e);
            return Result.fail(messages.get("quant.strategy.closeFailed", Map.of("reason", String.valueOf(e.getMessage()))));
        }
    }
}
