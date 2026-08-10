package com.mawai.wiibquant.agent.chat;

import com.mawai.wiibcommon.entity.UserLlmConfig;
import com.mawai.wiibquant.agent.trader.ApiKeyCrypto;
import com.mawai.wiibquant.agent.trader.BaseUrlGuard;
import com.mawai.wiibquant.agent.trader.TraderModelFactory;
import com.mawai.wiibquant.mapper.UserLlmConfigMapper;
import org.junit.jupiter.api.Test;

import java.util.Base64;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UserLlmConfigServiceTest {

    private static final String SECRET = Base64.getEncoder().encodeToString(new byte[32]);

    private final UserLlmConfigMapper mapper = mock(UserLlmConfigMapper.class);
    private final TraderModelFactory modelFactory = mock(TraderModelFactory.class);
    private final UserLlmConfigService service = new UserLlmConfigService(
            mapper, new ApiKeyCrypto(SECRET), new BaseUrlGuard(""), modelFactory);

    /** 字面 IP 而非域名：BaseUrlGuard 对主机名会真查 DNS，用域名等于让单测依赖网络 */
    private static UserLlmConfigService.SaveReq req(String baseUrl, String apiKey) {
        return new UserLlmConfigService.SaveReq("openai", baseUrl, "gpt-5", null, apiKey);
    }

    /** SSRF 防线必须接进每一个吃 baseUrl 的入口，配置保存是其中之一 */
    @Test
    void 内网baseUrl被拒绝() {
        assertThat(service.save(1L, req("http://127.0.0.1:8080", "sk-x"))).contains("内网");
    }

    /**
     * key 必须加密入库，绝不能落明文。
     * <b>断言点必须打在真正交给 mapper 的那个对象上</b>——mapper 是 mock，insert 什么都不做，
     * 若在测试里自己 new 一个对象自己加密再验尾号，把 save() 改成明文落库这条照样绿
     */
    @Test
    void key加密入库且回显只给尾四位() {
        when(mapper.selectById(1L)).thenReturn(null);
        AtomicReference<UserLlmConfig> inserted = new AtomicReference<>();
        when(mapper.insert(any(UserLlmConfig.class))).thenAnswer(inv -> {
            inserted.set(inv.getArgument(0));
            return 1;
        });

        assertThat(service.save(1L, req("https://8.8.8.8", "sk-abcd1234"))).isNull();

        assertThat(inserted.get().getApiKeyEnc()).isNotEqualTo("sk-abcd1234");
        assertThat(new ApiKeyCrypto(SECRET).decrypt(inserted.get().getApiKeyEnc()))
                .isEqualTo("sk-abcd1234");
        assertThat(service.keyTail(inserted.get())).isEqualTo("1234");
    }

    /**
     * 保存不发上游请求：连通性探测是独立按钮（见 testConnection / POST /test）。
     * 塞进 save() 的话端点抖一下用户就改不了模型名，每次保存还白烧一次 token
     */
    @Test
    void 保存不触发上游探测() {
        when(mapper.selectById(1L)).thenReturn(null);
        when(mapper.insert(any(UserLlmConfig.class))).thenReturn(1);

        service.save(1L, req("https://8.8.8.8", "sk-abcd1234"));

        verify(modelFactory, never()).testConnection(any());
    }

    /** 探测走独立入口，上游说不行就如实把原因交出去 */
    @Test
    void 连通性探测失败时返回原因() {
        when(mapper.selectById(1L)).thenReturn(null);
        when(modelFactory.testConnection(any())).thenReturn("401 Unauthorized");

        assertThat(service.testConnection(1L, req("https://8.8.8.8", "sk-x")))
                .contains("401");
    }

    /** 改配置时 key 留空 = 不换，与 trader 侧语义保持一致 */
    @Test
    void key留空表示不修改() {
        UserLlmConfig existing = new UserLlmConfig();
        existing.setUserId(1L);
        existing.setApiProtocol("openai");
        existing.setBaseUrl("https://8.8.8.8");
        existing.setModel("gpt-5");
        existing.setApiKeyEnc("old-enc");
        when(mapper.selectById(1L)).thenReturn(existing);
        AtomicReference<UserLlmConfig> updated = new AtomicReference<>();
        when(mapper.updateById(any(UserLlmConfig.class))).thenAnswer(inv -> {
            updated.set(inv.getArgument(0));
            return 1;
        });

        assertThat(service.save(1L, req("https://8.8.8.8", ""))).isNull();

        assertThat(updated.get().getApiKeyEnc()).isEqualTo("old-enc");   // 沿用旧密文
    }

    /** 没配过的用户拿到 null，准入层据此给"去配置"的引导（而不是当成系统错误） */
    @Test
    void 未配置时返回null() {
        when(mapper.selectById(9L)).thenReturn(null);

        assertThat(service.get(9L)).isNull();
    }
}
