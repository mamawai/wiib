package com.mawai.wiibcommon.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * AI Trader 持仓交易计划：开仓时立的论点/失效条件/止损止盈快照，每次唤醒原样回注提示词
 * （nof1 式 plan reinjection）——退出纪律的记忆载体，治恐慌平仓的根：醒来的模型不再是失忆的新人。
 * 键 (trader, round, symbol, side)：sim 同向开仓自动并入同一仓位，任意时刻至多一仓；加仓=新论点覆盖。
 */
@Data
@TableName("ai_trader_plan")
public class AiTraderPlan {

    public static final String STATUS_LIVE = "LIVE";
    public static final String STATUS_CLOSED = "CLOSED";

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long traderId;

    private Integer roundNo;

    private String symbol;

    /** LONG / SHORT */
    private String side;

    /** 论点标签：BREAKOUT/PULLBACK/REVERSAL/... */
    private String playType;

    /** 开仓依据的数据引用 */
    private String signalsUsed;

    /** 失效条件：什么市场状况证明论点错了（市场条件而非盈亏数字），触发才允许主动平仓 */
    private String invalidationCondition;

    /** 开仓时点入场参考价快照（市价=当时现价，限价=限价） */
    private BigDecimal entryPrice;

    /** 原始止损快照；当前生效止损以 sim 仓位为准（可能已上移锁盈） */
    private BigDecimal stopLossPrice;

    private BigDecimal takeProfitPrice;

    /** 开仓所在唤醒边界(ms)，回注时计算已持有时长 */
    private Long openedWakeTime;

    /**
     * 修订历史追加式 JSON [{time,type,change,reason}]：加仓覆盖/移动止盈/移动止损/补立。
     * 修改必须留痕带理由（回注给下轮无记忆的模型看）；计划本体价格字段永远是原始快照，当前生效单在 sim 仓位上。
     */
    private String revisionsJson;

    /**
     * LIVE=仓位/挂单存活 CLOSED=已了结归档。归档不删：论点→结局的配对数据是
     * reviewer 复盘的原料（结局按 symbol/side/时间窗 join sim 已平仓位）。
     */
    private String status;

    /** 归档时刻(ms)：懒清理发现仓位已了结的唤醒边界/重置时刻 */
    private Long closedWakeTime;

    /** 主人标记忽略：true=不进论点战绩统计与复盘教材；权益/排行榜/同侪视角照常。仅 CLOSED 可标，可逆 */
    private Boolean stale;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
