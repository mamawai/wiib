package com.mawai.wiibquant.agent.trader;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SSRF 防线：baseUrl 仅允许 http(s) + 公网地址；环境变量白名单主机先于 DNS 短路放行。
 * 测试全部离线确定：字面 IP 不发 DNS，localhost 由本机 hosts 解析。
 */
class BaseUrlGuardTest {

    private final BaseUrlGuard guard = new BaseUrlGuard("");

    @Test
    void publicLiteralIpAllowed() {
        assertThat(guard.check("https://8.8.8.8")).isNull();
        assertThat(guard.check("http://1.1.1.1:8317")).isNull();
    }

    @Test
    void loopbackAndPrivateRejected() {
        assertThat(guard.check("http://127.0.0.1:8080")).contains("内网");
        assertThat(guard.check("http://localhost:11434")).contains("内网");
        assertThat(guard.check("http://10.0.0.5")).contains("内网");
        assertThat(guard.check("http://192.168.1.5:8082")).contains("内网");
        assertThat(guard.check("http://172.18.0.3")).contains("内网");
    }

    /** 云元数据地址（169.254.x 链路本地）必须拒绝 */
    @Test
    void linkLocalMetadataRejected() {
        assertThat(guard.check("http://169.254.169.254")).contains("内网");
    }

    @Test
    void nonHttpSchemeRejected() {
        assertThat(guard.check("file:///etc/passwd")).contains("http");
        assertThat(guard.check("ftp://8.8.8.8")).contains("http");
    }

    @Test
    void malformedOrHostlessRejected() {
        assertThat(guard.check("not a url")).isNotNull();
        assertThat(guard.check("https://")).isNotNull();
    }

    /** 运维白名单主机先于 DNS 短路放行：docker 网络主机名在开发机上解析不了也要能过 */
    @Test
    void allowlistedHostBypassesResolution() {
        BaseUrlGuard g = new BaseUrlGuard("cliproxyapi, ollama-box");
        assertThat(g.check("http://cliproxyapi:8317")).isNull();
        assertThat(g.check("http://OLLAMA-BOX:11434")).isNull();
        // 白名单不影响其他主机照常拒绝
        assertThat(g.check("http://127.0.0.1")).contains("内网");
    }
}
