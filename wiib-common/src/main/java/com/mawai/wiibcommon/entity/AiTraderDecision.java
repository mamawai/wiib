package com.mawai.wiibcommon.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * AI Trader 每次唤醒一行：推理全文 + 动作（含 play_type 论点标签）+ 权益快照。
 * 竞技场决策时间线与净值曲线的唯一数据源。
 */
@Data
@TableName("ai_trader_decision")
public class AiTraderDecision {

    public static final String STATUS_OK = "OK";
    public static final String STATUS_ERROR = "ERROR";
    public static final String STATUS_SKIPPED = "SKIPPED";

    public static final String KIND_TRADE = "TRADE";
    public static final String KIND_ALERT = "ALERT";
    public static final String KIND_REVIEW = "REVIEW";

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long traderId;

    private Integer roundNo;

    /** 触发本次唤醒的K线边界时刻(ms)；ALERT 行是哨兵触发时刻，非边界 */
    private Long wakeTime;

    private String intervalCode;

    /** TRADE=例行K线唤醒 ALERT=波动哨兵警报唤醒 REVIEW=learning agent复盘（reasoning=复盘全文，无equity） */
    private String kind;

    /** OK / ERROR / SKIPPED（上一唤醒未完被跳过） */
    private String status;

    /** 唤醒时账户权益(USDT)，净值曲线数据源 */
    private BigDecimal equity;

    /** AI 最终回复全文——竞技场观赏核心 */
    private String reasoning;

    /** 本轮全部工具调用 JSON：[{tool,args,status,result/rejected/error}...]，交易动作带结构化论点标签 */
    private String actionsJson;

    private Integer toolCalls;

    /** 本轮模型调用次数：ReAct 是循环，一次唤醒会调很多次 */
    private Integer modelCalls;

    /** 本轮全部模型调用的 token 合计；null=上游端点没返回 usage，不是 0 */
    private Long promptTokens;
    private Long completionTokens;
    private Long totalTokens;

    private Integer latencyMs;

    private String error;

    /**
     * 仅 REVIEW 行：本期学习完的记忆快照存档（学习演进史，append-only）。
     * ai_trader.memory 是滚动覆盖的"生效版本"，历史版本只活在这一列。
     */
    private String memoryAfter;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
