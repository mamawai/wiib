package com.mawai.wiibquant.agent.llm;

/**
 * 把用户填的 baseUrl 补成官方 OpenAI SDK 认的形状。
 * <p>
 * 本项目对用户的约定是 baseUrl <b>不含</b> {@code /v1}（表单上就是这么写的），
 * {@link ResponsesChatModel} 自己拼 {@code /v1/responses} 走的正是这个约定。
 * 但官方 SDK 的约定相反——它的默认 baseUrl 就是 {@code https://api.openai.com/v1}，
 * <b>不会替你补</b>。
 * <p>
 * 两套约定撞在一起的后果很隐蔽：同一份配置，跑对话是好的（不经 SDK），
 * 一点「检测模型」就 404——因为那条路打的是 {@code {baseUrl}/models} 而不是
 * {@code {baseUrl}/v1/models}。
 * <p>
 * 所以凡是把 baseUrl 交给 SDK 的地方，都先过这里。
 */
public final class OpenAiBaseUrl {

    private OpenAiBaseUrl() {
    }

    /** 已经带 {@code /v1} 的原样返回——用户填了带后缀的地址不能被拼成 {@code /v1/v1}。 */
    public static String forSdk(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            return baseUrl;
        }
        String url = baseUrl.trim();
        while (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        return url.endsWith("/v1") ? url : url + "/v1";
    }
}
