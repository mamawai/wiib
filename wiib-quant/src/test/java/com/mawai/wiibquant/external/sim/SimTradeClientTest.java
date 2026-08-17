package com.mawai.wiibquant.external.sim;

import com.mawai.wiibcommon.dto.FuturesOpenRequest;
import com.mawai.wiibcommon.dto.FuturesOrderResponse;
import com.mawai.wiibcommon.dto.FuturesPositionDTO;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.ResourceAccessException;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * SimTradeApi 声明式接口的真实 HTTP 层验证（JDK 内置 HttpServer 假扮 sim）：
 * 路径模板展开、可选查询参数、鉴权头、Result&lt;T&gt; 泛型反序列化与业务失败拆壳。
 * SimExecutionServiceTest 的桩覆盖不到这些——它把 client 方法整个覆写掉了。
 */
class SimTradeClientTest {

    private static HttpServer server;
    private static SimTradeClient client;

    private static volatile String lastMethod;
    private static volatile URI lastUri;
    private static volatile String lastToken;
    private static volatile String lastBody;
    private static volatile String responseJson;
    private static volatile long responseDelayMs;

    @BeforeAll
    static void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/", exchange -> {
            lastMethod = exchange.getRequestMethod();
            lastUri = exchange.getRequestURI();
            lastToken = exchange.getRequestHeaders().getFirst("X-Internal-Token");
            lastBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            if (responseDelayMs > 0) {
                try {
                    Thread.sleep(responseDelayMs);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            }
            byte[] resp = responseJson.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, resp.length);
            exchange.getResponseBody().write(resp);
            exchange.close();
        });
        server.start();
        client = new SimTradeClient("http://localhost:" + server.getAddress().getPort(), "test-token");
    }

    @AfterAll
    static void stop() {
        server.stop(0);
    }

    @Test
    void 开仓_路径展开_请求体_鉴权头_泛型拆壳() {
        responseJson = "{\"code\":0,\"msg\":\"成功\",\"data\":{\"orderId\":123,\"status\":\"PENDING\"}}";
        FuturesOpenRequest req = new FuturesOpenRequest();
        req.setSymbol("ETHUSDT");

        FuturesOrderResponse resp = client.openPosition(42L, req);

        assertThat(lastMethod).isEqualTo("POST");
        assertThat(lastUri.getPath()).isEqualTo("/internal/futures/42/open");
        assertThat(lastToken).isEqualTo("test-token");
        assertThat(lastBody).contains("\"symbol\":\"ETHUSDT\"");
        assertThat(resp.getOrderId()).isEqualTo(123L);
        assertThat(resp.getStatus()).isEqualTo("PENDING");
    }

    @Test
    void 持仓查询_symbol传值带参数_传null省略参数() {
        responseJson = "{\"code\":0,\"msg\":\"成功\",\"data\":[{\"id\":7,\"status\":\"OPEN\"}]}";

        List<FuturesPositionDTO> positions = client.getPositions(42L, "ETHUSDT");
        assertThat(lastUri.toString()).isEqualTo("/internal/futures/42/positions?symbol=ETHUSDT");
        assertThat(positions).singleElement().satisfies(p -> assertThat(p.getId()).isEqualTo(7L));

        client.getAllPositions(42L);
        assertThat(lastUri.toString()).isEqualTo("/internal/futures/42/positions");
    }

    @Test
    void ensureAccount_POST查询参数_解析userId() {
        responseJson = "{\"code\":0,\"msg\":\"成功\",\"data\":{\"userId\":99,\"balance\":10000}}";

        Long userId = client.ensureAccount("quant-FIBO", new BigDecimal("10000"));

        assertThat(lastMethod).isEqualTo("POST");
        assertThat(lastUri.getPath()).isEqualTo("/internal/futures/ensure-account");
        assertThat(lastUri.getQuery()).contains("username=quant-FIBO").contains("initialBalance=10000");
        assertThat(userId).isEqualTo(99L);
    }

    @Test
    void deleteAccount_POST查询参数_业务失败拆壳抛异常() {
        responseJson = "{\"code\":0,\"msg\":\"成功\",\"data\":null}";
        client.deleteAccount("ai_trader_1_r1");
        assertThat(lastMethod).isEqualTo("POST");
        assertThat(lastUri.getPath()).isEqualTo("/internal/futures/delete-account");
        assertThat(lastUri.getQuery()).contains("username=ai_trader_1_r1");

        // 护栏拒删（比如误传 quant-FIBO）走 Result.fail，必须抛出来而不是静默当成功
        responseJson = "{\"code\":500,\"msg\":\"仅允许删除 ai_trader 量化子账户\",\"data\":null}";
        assertThatThrownBy(() -> client.deleteAccount("quant-FIBO"))
                .hasMessageContaining("仅允许删除");
    }

    /**
     * 读超时抛什么，AI Trader 的"同键重发确认"整条链路都押在这上面：必须是
     * ResourceAccessException 而不是业务失败那种 IllegalStateException，
     * 否则 isTransportFailure 认不出来，超时会被当明确失败回给模型，模型重下就是双仓。
     */
    @Test
    void 读超时抛ResourceAccessException_供同键重发识别() {
        responseJson = "{\"code\":0,\"msg\":\"成功\",\"data\":{\"orderId\":1}}";
        responseDelayMs = 6000; // 客户端读超时 5s
        try {
            Throwable thrown = catchThrowable(() -> client.openPosition(42L, new FuturesOpenRequest()));

            assertThat(thrown).isInstanceOf(ResourceAccessException.class);
            assertThat(SimTradeClient.isTransportFailure(thrown)).isTrue();
        } finally {
            responseDelayMs = 0;
        }
    }

    /** sim 幂等占位回的"处理中"：错误码 1105 得能认出来，认成普通业务失败就不会去重发确认了 */
    @Test
    void 处理中按错误码识别_不与普通业务失败混淆() {
        responseJson = "{\"code\":1105,\"msg\":\"请求处理中，请稍后用同一 clientRequestId 重试\",\"data\":null}";
        Throwable processing = catchThrowable(() -> client.openPosition(42L, new FuturesOpenRequest()));
        assertThat(SimTradeClient.isProcessing(processing)).isTrue();

        responseJson = "{\"code\":1751,\"msg\":\"余额不足\",\"data\":null}";
        Throwable business = catchThrowable(() -> client.openPosition(42L, new FuturesOpenRequest()));
        assertThat(SimTradeClient.isProcessing(business)).isFalse();
        assertThat(SimTradeClient.isTransportFailure(business)).isFalse();
    }

    @Test
    void 业务失败200加Resultfail_拆壳转异常() {
        responseJson = "{\"code\":500,\"msg\":\"余额不足\",\"data\":null}";

        assertThatThrownBy(() -> client.getBalance(42L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("余额不足");
    }
}
