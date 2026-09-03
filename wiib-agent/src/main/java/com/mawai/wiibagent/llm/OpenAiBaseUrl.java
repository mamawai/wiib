package com.mawai.wiibagent.llm;

/**
 * 用户填的 baseUrl 与各条协议路径约定之间的接缝。
 * <p>
 * 本项目对用户的约定是 baseUrl <b>不含</b>版本后缀（表单上就是这么写的），自研协议
 * 自己拼 {@code /v1/responses}、{@code /v1/messages}、{@code /v1beta/models/...}；
 * 官方 OpenAI SDK 的约定相反——它的默认 baseUrl 就是 {@code https://api.openai.com/v1}，不会替你补。
 * 用户手滑带上后缀也不能坏：交给 SDK 的先 {@link #forSdk}，自拼路径的先 {@link #strip}。
 */
public final class OpenAiBaseUrl {

    private OpenAiBaseUrl() {
    }

    /** 已经带 {@code /v1} 的原样返回——用户填了带后缀的地址不能被拼成 {@code /v1/v1}。 */
    public static String forSdk(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            return baseUrl;
        }
        String url = trimSlashes(baseUrl);
        return url.endsWith("/v1") ? url : url + "/v1";
    }

    /** 剥掉尾部的版本后缀（{@code /v1}、{@code /v1beta}…）与尾斜杠；自拼路径的协议都先过这里，与 {@link #forSdk} 互为镜像 */
    public static String strip(String baseUrl, String suffix) {
        if (baseUrl == null || baseUrl.isBlank()) {
            return baseUrl;
        }
        String url = trimSlashes(baseUrl);
        if (url.endsWith(suffix)) {
            url = trimSlashes(url.substring(0, url.length() - suffix.length()));
        }
        return url;
    }

    private static String trimSlashes(String url) {
        String out = url.trim();
        while (out.endsWith("/")) {
            out = out.substring(0, out.length() - 1);
        }
        return out;
    }
}
