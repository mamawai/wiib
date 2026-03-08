package com.mawai.wiibservice.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.mawai.wiibcommon.dto.*;

import java.math.BigDecimal;
import java.util.List;

public interface FuturesService {

    /** 开仓 */
    FuturesOrderResponse openPosition(Long userId, FuturesOpenRequest request);

    /** 平仓 */
    FuturesOrderResponse closePosition(Long userId, FuturesCloseRequest request);

    /** 取消限价单 */
    FuturesOrderResponse cancelOrder(Long userId, Long orderId);

    /** 追加保证金 */
    void addMargin(Long userId, FuturesAddMarginRequest request);

    /** 设置止损 */
    void setStopLoss(Long userId, FuturesStopLossRequest request);

    /** 查询用户仓位列表 */
    List<FuturesPositionDTO> getUserPositions(Long userId, String symbol);

    /** 查询用户订单列表 */
    IPage<FuturesOrderResponse> getUserOrders(Long userId, String status, int pageNum, int pageSize, String symbol);

    /** 最新成交20条-匿名 */
    List<FuturesOrderResponse> getLatestOrders();

    /** 价格更新时触发限价单检查（由BinanceWsClient调用） */
    void onPriceUpdate(String symbol, BigDecimal price);

    /** 恢复限价单（重启后） */
    void recoverLimitOrders(String symbol, BigDecimal periodLow, BigDecimal periodHigh);

    /** 过期限价单处理 */
    void expireLimitOrders();

    /** 补处理TRIGGERED孤儿单 */
    void executeTriggeredOrders();

    /** 资金费率扣除（每8h） */
    void chargeFundingFeeAll();

    /** 强制平仓 */
    void forceClose(Long positionId, BigDecimal price);
}
