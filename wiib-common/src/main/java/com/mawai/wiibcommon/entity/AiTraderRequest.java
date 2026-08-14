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
 * AI Trader 加仓/减仓待确认请求：allowSelfAdd/allowSelfReduce 关掉时，模型的工具调用转成这里一行。
 * 异步不阻塞——落库即返回，本轮唤醒照常跑完收尾；主人在"我的 trader"页点同意才市价执行。
 * 不设过期：卡片同时给"请求时价"与实时价，价格跑没跑掉交给人自己判断。
 */
@Data
@TableName("ai_trader_request")
public class AiTraderRequest {

    public static final String TYPE_ADD = "ADD";
    public static final String TYPE_REDUCE = "REDUCE";

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_APPROVED = "APPROVED";
    public static final String STATUS_REJECTED = "REJECTED";

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long traderId;

    private Integer roundNo;

    /** ADD=加仓 / REDUCE=减仓或平仓 */
    private String type;

    private String symbol;

    /** LONG / SHORT */
    private String side;

    /** 目标仓位 id（sim 侧）；批准时重查存在性，已被止损带走则置失败 */
    private Long positionId;

    private BigDecimal quantity;

    /** 加仓用；减仓为 null */
    private Integer leverage;

    /** 模型发起时的 mark 快照，与实时价并列展示供主人判断价格是否跑掉 */
    private BigDecimal requestPrice;

    private String reason;

    /** PENDING / APPROVED / REJECTED */
    private String status;

    /** 批准后的执行结果或失败原因（余额不足/仓位已不存在等），不吞 */
    @TableField(updateStrategy = com.baomidou.mybatisplus.annotation.FieldStrategy.ALWAYS)
    private String executedResult;

    /** 处理结果是否已回注给模型：批/拒后的下一次唤醒注入一次并置 true（反馈闭环最后一环） */
    private Boolean notified;

    /** 发起时所在唤醒边界(ms)，回注提示词时说明这是第几轮提的 */
    private Long wakeTime;

    @TableField(updateStrategy = com.baomidou.mybatisplus.annotation.FieldStrategy.ALWAYS)
    private LocalDateTime decidedAt;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
