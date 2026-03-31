package com.mawai.wiibcommon.util;

public class JsonUtils {

    private JsonUtils() {}

    /**
     * 从文本中提取第一个JSON对象。
     * 自动移除 {@code <think>...</think>} 等模型思考标签及 markdown 代码块包裹。
     */
    public static String extractJson(String text) {
        if (text == null) return "{}";
        // 移除 <think>...</think> 标签（含换行）
        String cleaned = text.replaceAll("(?s)<think>.*?</think>", "").trim();
        // 移除 markdown 代码块包裹 ```json ... ```
        cleaned = cleaned.replaceAll("(?s)^```(?:json)?\\s*", "").replaceAll("(?s)\\s*```$", "");
        int start = cleaned.indexOf('{');
        int end = cleaned.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return cleaned.substring(start, end + 1).replace("\r", "").replace("\n", " ");
        }
        return "{}";
    }
}
