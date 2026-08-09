package com.mawai.wiibquant.agent.trader;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * BYOK baseUrl 的 SSRF 防线：仅 http(s) + 公网地址——服务器会向该地址发请求，
 * 放任内网地址等于把 quant 变成任意用户的内网探测器（云上还有 169.254 元数据服务）。
 * 运维白名单（环境变量 WIIB_TRADER_BASEURL_ALLOWLIST，逗号分隔主机名）先于 DNS 短路放行，
 * 服务于 docker 同网络的代理网关（如 cliproxyapi）。
 * 明确不防 DNS rebinding（校验时解析一次，请求时不钉连接层）：攻击成本高、模拟盘收益低，接受残余风险。
 */
@Component
public class BaseUrlGuard {

    private final Set<String> allowlist;

    public BaseUrlGuard(@Value("${WIIB_TRADER_BASEURL_ALLOWLIST:}") String allowlistCsv) {
        this.allowlist = allowlistCsv == null || allowlistCsv.isBlank()
                ? Set.of()
                : Arrays.stream(allowlistCsv.split(","))
                        .map(s -> s.trim().toLowerCase(Locale.ROOT))
                        .filter(s -> !s.isEmpty())
                        .collect(Collectors.toSet());
    }

    /** 校验 baseUrl；返回给用户看的错误文案，通过返回 null。 */
    public String check(String baseUrl) {
        URI uri;
        try {
            uri = new URI(baseUrl.trim());
        } catch (Exception e) {
            return "baseUrl 不是合法 URL";
        }
        String scheme = uri.getScheme();
        if (scheme == null || !("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))) {
            return "baseUrl 仅支持 http/https";
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            return "baseUrl 缺少主机名";
        }
        if (allowlist.contains(host.toLowerCase(Locale.ROOT))) {
            return null;
        }
        InetAddress[] addrs;
        try {
            addrs = InetAddress.getAllByName(host);
        } catch (UnknownHostException e) {
            return "baseUrl 主机名无法解析";
        }
        for (InetAddress a : addrs) {
            if (a.isLoopbackAddress() || a.isSiteLocalAddress() || a.isLinkLocalAddress()
                    || a.isAnyLocalAddress() || a.isMulticastAddress()
                    || isUniqueLocalV6(a) || isSharedAddressSpace(a)) {
                return "baseUrl 不允许指向内网/本机地址";
            }
        }
        return null;
    }

    /** IPv6 unique-local fc00::/7：isSiteLocalAddress 只认已废弃的 fec0::/10，这段要手判 */
    private static boolean isUniqueLocalV6(InetAddress a) {
        return a instanceof Inet6Address && (a.getAddress()[0] & 0xFE) == 0xFC;
    }

    /**
     * RFC 6598 共享地址空间 100.64.0.0/10：运营商级 NAT 用它，多家云也拿它当内网服务段
     * （阿里云元数据 100.100.100.200、内网 DNS 100.100.2.136 都在这段）。
     * isSiteLocalAddress 只认 10/172.16/192.168，这一段是它的盲区
     */
    private static boolean isSharedAddressSpace(InetAddress a) {
        if (a instanceof Inet6Address) {
            return false;
        }
        byte[] b = a.getAddress();
        // 100.64.0.0/10 = 第一字节 100（8 位全定）+ 第二字节高 2 位为 0b01（即 64..127）
        return (b[0] & 0xFF) == 100 && (b[1] & 0xC0) == 0x40;
    }
}
