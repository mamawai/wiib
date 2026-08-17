package com.mawai.wiibcommon.constant;

/**
 * AI 功能位名（ai_model_assignment.function_name 的契约值），现只有 quant 进程使用。
 * 前端 Admin.tsx 的 FUNCTION_LABELS 需与此同步。
 */
public final class AiFunctions {

    /** 行为分析（对话轨已全量 BYOK，见 user_llm_endpoint，平台不再为对话建模型） */
    public static final String BEHAVIOR = "behavior";

    /** 快讯打标：NewsEventCollector 后台批量打标用的轻模型，内部调用不走用户 key */
    public static final String NEWS_TAGGING = "news-tagging";

    private AiFunctions() {
    }
}
