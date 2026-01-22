package com.mawai.wiibcommon.enums;

import lombok.Getter;
import lombok.AllArgsConstructor;

@Getter
@AllArgsConstructor
public enum ErrorCode {

    SUCCESS(0, "成功"),
    PARAM_ERROR(400, "参数错误"),
    UNAUTHORIZED(401, "未登录"),
    FORBIDDEN(403, "无权限"),
    NOT_FOUND(404, "资源不存在"),
    SYSTEM_ERROR(500, "系统错误"),

    // 业务错误码 1000+
    USER_NOT_FOUND(1001, "用户不存在"),
    STOCK_NOT_FOUND(1002, "股票不存在"),
    BALANCE_NOT_ENOUGH(1003, "余额不足"),
    POSITION_NOT_ENOUGH(1004, "持仓不足"),
    TRADE_QUANTITY_INVALID(1005, "交易数量无效"),
    MARKET_CLOSED(1006, "市场已休市"),
    ORDER_NOT_FOUND(1007, "订单不存在"),
    ORDER_CANNOT_CANCEL(1008, "订单无法取消"),
    DUPLICATE_REQUEST(1009, "重复请求"),
    LIMIT_PRICE_INVALID(1011, "限价无效"),

    // 并发控制错误码 1100+
    CONCURRENT_UPDATE_FAILED(1101, "并发更新失败，请重试"),
    FROZEN_BALANCE_NOT_ENOUGH(1102, "冻结余额不足"),
    FROZEN_POSITION_NOT_ENOUGH(1103, "冻结持仓不足"),
    ACQUIRE_LOCK_FAILED(1104, "获取锁失败，请稍后重试"),
    ORDER_PROCESSING(1105, "订单正在处理中，请稍后再试"),

    // 交易限制错误码 1200+
    NOT_IN_TRADING_HOURS(1201, "非交易时段"),
    SLIPPAGE_EXCEEDED(1202, "价格波动过大，请重新下单"),
    RATE_LIMIT_EXCEEDED(1203, "请求过于频繁，请稍后再试"),
    USER_BANKRUPT(1204, "已爆仓，交易已禁用"),
    LEVERAGE_ONLY_FOR_MARKET_BUY(1205, "杠杆仅支持市价买入"),
    LEVERAGE_MULTIPLE_INVALID(1206, "杠杆倍率无效"),

    // WebSocket错误码 1300+
    WEBSOCKET_CONNECTION_LIMIT(1301, "连接数已达上限"),
    WEBSOCKET_AUTH_REQUIRED(1302, "需要登录后连接"),

    // Buff错误码 1400+
    BUFF_ALREADY_DRAWN(1401, "今日已抽奖"),
    BUFF_NOT_FOUND(1402, "Buff不存在"),
    BUFF_EXPIRED(1403, "Buff已过期"),
    BUFF_ALREADY_USED(1404, "Buff已使用"),
    DISCOUNT_NO_LEVERAGE(1405, "使用折扣时不支持杠杆");

    private final int code;
    private final String msg;
}
