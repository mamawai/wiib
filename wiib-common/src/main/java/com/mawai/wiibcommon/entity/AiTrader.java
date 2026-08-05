package com.mawai.wiibcommon.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * AI Trader：用户 BYOK 自主交易代理（每用户 1 个，独立 sim 子账户，公开竞技场）。
 * api_key_enc 是 AES-GCM 密文，只在构建 ChatModel 的瞬间解密，绝不进日志。
 */
@Data
@TableName("ai_trader")
public class AiTrader {

    public static final String STATUS_PAUSED = "PAUSED";
    public static final String STATUS_RUNNING = "RUNNING";
    public static final String STATUS_LIQUIDATED = "LIQUIDATED";

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private String name;

    /** PAUSED / RUNNING / LIQUIDATED */
    private String status;

    /** 暂停原因（连败自动暂停/爆仓终局）；手动恢复时清空。ALWAYS：清空=写 null */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String pausedReason;

    /** 交易币种白名单子集，逗号分隔，如 BTCUSDT,ETHUSDT */
    private String symbols;

    /** 唤醒K线级别：15m/1h/4h/1d */
    private String intervalCode;

    /** 用户自定义提示词，追加在平台系统提示词之后；每次唤醒现读现拼，改完下一根K线生效 */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String customPrompt;

    /** 上游协议：openai=/v1/chat/completions，responses=/v1/responses */
    private String apiProtocol;

    private String baseUrl;

    private String model;

    /** AES-GCM 密文 base64(iv+cipher) */
    private String apiKeyEnc;

    /** 当前局 sim 子账户 userId（每局一个独立子账户，重置开新局） */
    private Long simUserId;

    /** 局数：爆仓/手动重置后 +1 开新局，历史决策与战绩留档 */
    private Integer roundNo;

    /** 连续唤醒失败计数：成功清零，≥5 自动 PAUSED */
    private Integer consecutiveFailures;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
