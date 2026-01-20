package com.mawai.wiibservice.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.mawai.wiibcommon.dto.StockDTO;
import com.mawai.wiibcommon.util.Result;
import com.mawai.wiibservice.service.MarketDataService;
import com.mawai.wiibservice.service.StockService;
import com.mawai.wiibservice.service.impl.StockServiceImpl;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 股票Controller
 */
@Tag(name = "股票接口")
@RestController
@RequestMapping("/api/stock")
@RequiredArgsConstructor
public class StockController {

    private final StockService stockService;
    private final StockServiceImpl stockServiceImpl;
    private final MarketDataService marketDataService;

    /**
     * 获取所有股票列表
     */
    @GetMapping("/list")
    @Operation(summary = "获取所有股票列表")
    public Result<List<StockDTO>> listAllStocks() {
        List<StockDTO> stocks = stockService.listAllStocks();
        return Result.ok(stocks);
    }

    /**
     * 分页查询股票列表
     */
    @GetMapping("/page")
    @Operation(summary = "分页查询股票列表")
    public Result<Page<StockDTO>> listStocksByPage(
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "10") int pageSize) {
        Page<StockDTO> page = stockServiceImpl.listStocksByPage(pageNum, pageSize);
        return Result.ok(page);
    }

    /**
     * 获取股票详情
     */
    @GetMapping("/{code}")
    @Operation(summary = "获取股票详情")
    public Result<StockDTO> getStockDetail(@PathVariable String code) {
        StockDTO stock = stockService.getStockDetail(code);
        return Result.ok(stock);
    }

    /**
     * 获取涨幅榜
     */
    @GetMapping("/gainers")
    @Operation(summary = "获取涨幅榜")
    public Result<List<StockDTO>> getTopGainers(@RequestParam(defaultValue = "10") int limit) {
        List<StockDTO> gainers = stockService.getTopGainers(limit);
        return Result.ok(gainers);
    }

    /**
     * 获取跌幅榜
     */
    @GetMapping("/losers")
    @Operation(summary = "获取跌幅榜")
    public Result<List<StockDTO>> getTopLosers(@RequestParam(defaultValue = "10") int limit) {
        List<StockDTO> losers = stockService.getTopLosers(limit);
        return Result.ok(losers);
    }

    /**
     * 获取当日分时数据
     */
    @GetMapping("/{stockId}/ticks")
    @Operation(summary = "获取当日分时数据")
    public Result<List<Map<String, Object>>> getDayTicks(
            @PathVariable Long stockId,
            @RequestParam(required = false) LocalDate date) {
        LocalDate queryDate = date != null ? date : LocalDate.now();
        List<Map<String, Object>> ticks = marketDataService.getDayTicks(stockId, queryDate);
        return Result.ok(ticks);
    }

    /**
     * 获取历史收盘价
     */
    @GetMapping("/{stockId}/history")
    @Operation(summary = "获取历史收盘价")
    public Result<List<Map<String, Object>>> getHistoryClose(
            @PathVariable Long stockId,
            @RequestParam(defaultValue = "30") int days) {
        List<Map<String, Object>> history = marketDataService.getHistoryClose(stockId, days);
        return Result.ok(history);
    }
}
