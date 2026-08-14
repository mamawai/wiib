package com.mawai.wiibcommon.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/** 用户自带 LLM 端点配置（BYOK）。一人一行，user_id 即主键。 */
@Data
@TableName("user_llm_config")
public class UserLlmConfig {

    /** 主键是 userId 由调用方给，不是自增 */
    @TableId(type = IdType.INPUT)
    private Long userId;

    private String apiProtocol;
    private String baseUrl;
    private String model;

    /** 可空：空则 router/专家/历史压缩复用 model。ALWAYS：清空轻模型是合法操作，默认策略会跳过 null */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String lightModel;

    /**
     * 思考档位 none/low/medium/high，可空=不传走模型默认。只作用于 model，轻模型不带
     *（跑 router/专家/历史压缩这些简单活，高档纯烧钱烧延迟）。
     * ALWAYS 的理由同 lightModel：改回"不传"是合法操作。
     */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String reasoningEffort;

    private String apiKeyEnc;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
