package com.mawai.wiibagent.llm;

/**
 * 把用户填的 baseUrl 补成官方 OpenAI SDK 认的形状。
 * <p>
 * 本项目对用户的约定是 baseUrl <b>不含</b> {@code /v1}（表单上就是这么写的），
 * {@link ResponsesChatModel} 自己拼 {@code /v1/responses} 走的正是这个约定。
 * 但官方 SDK 的约定相反——它的默认 baseUrl 就是 {@code https://api.openai.com/v1}，
 * <b>不会替你补</b>。
 * <p>
 * 凡是把 baseUrl 交给 SDK 的地方都先过这里，两套约定才对得上。
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

    /**
     * Responses 路（{@link ResponsesChatModel} 自拼 {@code /v1/responses}）：带了 {@code /v1} 的剥掉。
     * 与 {@link #forSdk} 互为镜像——不镜像的话同一份带 /v1 的配置 openai 协议好用、
     * responses 协议打到 {@code /v1/v1/responses} 拿 404，用户只会觉得"换个协议就坏了"。
     */
    public static String forResponses(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            return baseUrl;
        }
        String url = baseUrl.trim();
        while (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        if (url.endsWith("/v1")) {
            url = url.substring(0, url.length() - 3);
        }
        while (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        return url;
    }
}
