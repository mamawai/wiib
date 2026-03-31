package com.mawai.wiibservice.agent.behavior;

import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.mawai.wiibcommon.entity.*;
import com.mawai.wiibservice.mapper.*;
import com.mawai.wiibservice.service.CryptoPositionService;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.function.Consumer;

@RequiredArgsConstructor
public class BehaviorAnalysisTools {

    private final UserMapper userMapper;
    private final UserAssetSnapshotMapper snapshotMapper;
    private final PositionMapper positionMapper;
    private final OrderMapper orderMapper;
    private final CryptoOrderMapper cryptoOrderMapper;
    private final CryptoPositionService cryptoPositionService;
    private final FuturesOrderMapper futuresOrderMapper;
    private final FuturesPositionMapper futuresPositionMapper;
    private final OptionOrderMapper optionOrderMapper;
    private final PredictionBetMapper predictionBetMapper;
    private final BlackjackAccountMapper blackjackAccountMapper;
    private final MinesGameMapper minesGameMapper;
    private final VideoPokerGameMapper videoPokerGameMapper;
    private final Consumer<String> onProgress;

    private void emitStep(String message) {
        if (onProgress != null) onProgress.accept(message);
    }

    @Tool(description = "获取用户基础信息：余额、冻结余额、破产次数、注册时间")
    public String getUserProfile(@ToolParam(description = "用户ID") Long userId) {
        emitStep("查询用户基础信息");
        User user = userMapper.selectById(userId);
        if (user == null) return "用户不存在";
        return JSON.toJSONString(new Object() {
            public final BigDecimal balance = user.getBalance();
            public final BigDecimal frozenBalance = user.getFrozenBalance();
            public final int bankruptCount = user.getBankruptCount();
            public final boolean isBankrupt = user.getIsBankrupt();
            public final Object bankruptAt = user.getBankruptAt();
            public final Object createdAt = user.getCreatedAt();
        });
    }

    @Tool(description = "获取用户近30日资产快照，含各品类盈亏趋势")
    public String getAssetSnapshots(@ToolParam(description = "用户ID") Long userId) {
        emitStep("获取近30日资产快照");
        List<UserAssetSnapshot> snapshots = snapshotMapper.listByUserAndDateRange(userId, LocalDate.now().minusDays(30));
        return JSON.toJSONString(snapshots);
    }

    @Tool(description = "获取用户股票持仓列表")
    public String getStockPositions(@ToolParam(description = "用户ID") Long userId) {
        emitStep("查询股票持仓");
        List<Position> positions = positionMapper.selectList(
                new LambdaQueryWrapper<Position>().eq(Position::getUserId, userId));
        return JSON.toJSONString(positions);
    }

    @Tool(description = "获取用户股票交易统计：买入总额、订单数、买卖偏好、订单类型偏好")
    public String getStockTradeStats(@ToolParam(description = "用户ID") Long userId) {
        emitStep("统计股票交易数据");
        BigDecimal buyTotal = orderMapper.sumBuyFilledAmount(userId);
        long count = orderMapper.countFilledOrders(userId);
        String sidePref = orderMapper.selectSidePreference(userId);
        String orderTypePref = orderMapper.selectOrderTypePreference(userId);
        return JSON.toJSONString(new Object() {
            public final BigDecimal totalBuyAmount = buyTotal;
            public final long filledOrderCount = count;
            public final String sidePreference = sidePref;
            public final String orderTypePreference = orderTypePref;
        });
    }

    @Tool(description = "获取用户加密货币交易统计：买入总额、卖出总额、持仓数、杠杆使用情况")
    public String getCryptoTradeStats(@ToolParam(description = "用户ID") Long userId) {
        emitStep("统计加密货币交易数据");
        BigDecimal buyTotal = cryptoOrderMapper.sumBuyFilledAmount(userId);
        BigDecimal sellTotal = cryptoOrderMapper.sumSellFilledAmount(userId);
        List<?> positions = cryptoPositionService.getUserPositions(userId);
        BigDecimal avgLev = cryptoOrderMapper.selectAvgLeverage(userId);
        String levUsage = classifyLeverageUsage(avgLev);
        return JSON.toJSONString(new Object() {
            public final BigDecimal totalBuyAmount = buyTotal;
            public final BigDecimal totalSellAmount = sellTotal;
            public final int positionCount = positions.size();
            public final BigDecimal avgLeverage = avgLev;
            public final String leverageUsage = levUsage;
        });
    }

    private String classifyLeverageUsage(BigDecimal avgLeverage) {
        if (avgLeverage == null || avgLeverage.compareTo(BigDecimal.ONE) <= 0) return "NONE";
        if (avgLeverage.compareTo(BigDecimal.valueOf(2)) <= 0) return "LOW";
        if (avgLeverage.compareTo(BigDecimal.valueOf(5)) <= 0) return "MEDIUM";
        return "HIGH";
    }

    @Tool(description = "获取用户合约交易统计：已实现盈亏、订单数、多空偏好、平均杠杆、止损率、爆仓次数")
    public String getFuturesTradeStats(@ToolParam(description = "用户ID") Long userId) {
        emitStep("统计合约交易数据");
        BigDecimal pnl = futuresOrderMapper.sumRealizedPnl(userId);
        long count = futuresOrderMapper.countFilledOrders(userId);
        String dir = futuresOrderMapper.selectDirectionPreference(userId);
        BigDecimal lev = futuresOrderMapper.selectAvgLeverage(userId);
        BigDecimal slRate = futuresPositionMapper.selectStopLossRate(userId);
        int liqCount = futuresPositionMapper.countLiquidatedPositions(userId);
        return JSON.toJSONString(new Object() {
            public final BigDecimal realizedPnl = pnl;
            public final long orderCount = count;
            public final String direction = dir;
            public final BigDecimal avgLeverage = lev;
            public final BigDecimal stopLossRate = slRate;
            public final int liquidationCount = liqCount;
        });
    }

    @Tool(description = "获取用户期权交易统计：买入总额(BTO)、卖出总额(STC)")
    public String getOptionTradeStats(@ToolParam(description = "用户ID") Long userId) {
        emitStep("统计期权交易数据");
        BigDecimal bto = optionOrderMapper.sumBtoFilledAmount(userId);
        BigDecimal stc = optionOrderMapper.sumStcFilledAmount(userId);
        return JSON.toJSONString(new Object() {
            public final BigDecimal totalBtoAmount = bto;
            public final BigDecimal totalStcAmount = stc;
        });
    }

    @Tool(description = "获取用户Prediction统计：参与次数、净盈亏、胜率、方向偏好")
    public String getPredictionStats(@ToolParam(description = "用户ID") Long userId) {
        emitStep("统计预测交易数据");
        BigDecimal profit = predictionBetMapper.sumRealizedProfit(userId);
        int freq = predictionBetMapper.countSettledBets(userId);
        BigDecimal rate = predictionBetMapper.selectWinRate(userId);
        String dirPref = predictionBetMapper.selectDirectionPreference(userId);
        return JSON.toJSONString(new Object() {
            public final int frequency = freq;
            public final BigDecimal netProfit = profit;
            public final BigDecimal winRate = rate;
            public final String directionPreference = dirPref;
        });
    }

    @Tool(description = "获取用户Blackjack统计：总局数、净赢、净输、最大赢、当日已转出积分")
    public String getBlackjackStats(@ToolParam(description = "用户ID") Long userId) {
        emitStep("获取 Blackjack 游戏数据");
        BlackjackAccount account = blackjackAccountMapper.selectOne(
                new LambdaQueryWrapper<BlackjackAccount>().eq(BlackjackAccount::getUserId, userId));
        if (account == null) {
            return "{\"totalHands\":0,\"totalWon\":0,\"totalLost\":0,\"biggestWin\":0,\"todayConverted\":0}";
        }
        return JSON.toJSONString(new Object() {
            public final long totalHands = account.getTotalHands();
            public final long totalWon = account.getTotalWon();
            public final long totalLost = account.getTotalLost();
            public final long biggestWin = account.getBiggestWin();
            public final long todayConverted = account.getTodayConverted();
        });
    }

    @Tool(description = "获取用户Mines统计：参与次数、净盈亏")
    public String getMinesStats(@ToolParam(description = "用户ID") Long userId) {
        emitStep("获取 Mines 游戏数据");
        int freq = minesGameMapper.countFinishedGames(userId);
        BigDecimal profit = minesGameMapper.sumNetProfit(userId);
        return JSON.toJSONString(new Object() {
            public final int frequency = freq;
            public final BigDecimal netProfit = profit;
        });
    }

    @Tool(description = "获取用户Video Poker统计：参与次数、净盈亏")
    public String getVideoPokerStats(@ToolParam(description = "用户ID") Long userId) {
        emitStep("获取 Video Poker 游戏数据");
        int freq = videoPokerGameMapper.countSettledGames(userId);
        BigDecimal profit = videoPokerGameMapper.sumNetProfit(userId);
        return JSON.toJSONString(new Object() {
            public final int frequency = freq;
            public final BigDecimal netProfit = profit;
        });
    }
}