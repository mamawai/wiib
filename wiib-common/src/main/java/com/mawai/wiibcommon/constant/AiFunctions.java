package com.mawai.wiibcommon.constant;

/**
 * AI 功能位名（ai_model_assignment.function_name 的契约值），现只有 quant 进程使用。
 * 前端 Admin.tsx 的 FUNCTION_LABELS 需与此同步。
 */
public final class AiFunctions {

    /** quant：用户行为分析 ReactAgent */
    public static final String BEHAVIOR = "behavior";
    /** quant：深研判/对话 Supervisor（深模型） */
    public static final String QUANT = "quant";
    /** quant：对话专家子 agent（浅模型） */
    public static final String QUANT_LIGHT = "quant-light";
    /** quant：对话 ModelFallback 兜底 */
    public static final String CHAT = "chat";

    private AiFunctions() {
    }
}
