package com.mawai.wiibcommon.constant;

/**
 * AI 功能位名（ai_model_assignment.function_name 的契约值），现只有 quant 进程使用。
 * 前端 Admin.tsx 的 FUNCTION_LABELS 需与此同步。
 */
public final class AiFunctions {

    /** 平台唯一还在用的功能位——对话轨已全量 BYOK（见 user_llm_config），平台不再为对话建模型 */
    public static final String BEHAVIOR = "behavior";

    private AiFunctions() {
    }
}
