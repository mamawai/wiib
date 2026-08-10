package com.mawai.wiibquant.agent.chat;

import com.mawai.wiibcommon.entity.AiTrader;
import com.mawai.wiibcommon.entity.UserLlmConfig;
import com.mawai.wiibquant.agent.trader.ApiKeyCrypto;
import com.mawai.wiibquant.agent.trader.BaseUrlGuard;
import com.mawai.wiibquant.agent.trader.TraderModelFactory;
import com.mawai.wiibquant.mapper.UserLlmConfigMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

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
        return new UserLlmConfigService.SaveReq("openai", baseUrl, "gpt-5", null, null, apiKey);
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

    /**
     * 拉模型清单是第二个吃 baseUrl 并真发出站请求的入口，走的是它自己那份 check——
     * save 那条钉子管不到这里，拆了闸门也没人报警，所以单独钉
     */
    @Test
    void 拉模型清单同样拦内网baseUrl() {
        UserLlmConfigService.ListModelsResult r = service.listModels(1L, req("http://127.0.0.1:8080", "sk-x"));

        verify(modelFactory, never()).listModels(any());   // 先钉住"请求根本没发出去"
        assertThat(r.error()).contains("内网");
    }

    /**
     * 探测递给上游的必须就是本次请求的这份配置，否则"测通了但存进去的不是它"。
     * 只断言返回串不够：换成一份完全不相干的配置照样能返回 401
     */
    @Test
    void 探测用的正是本次请求的配置() {
        when(mapper.selectById(1L)).thenReturn(null);
        when(modelFactory.testConnection(any())).thenReturn(null);

        assertThat(service.testConnection(1L, new UserLlmConfigService.SaveReq(
                "responses", "https://8.8.8.8/", " gpt-5-pro ", null, null, "sk-abcd1234"))).isNull();

        ArgumentCaptor<AiTrader> probe = ArgumentCaptor.forClass(AiTrader.class);
        verify(modelFactory).testConnection(probe.capture());
        assertThat(probe.getValue().getApiProtocol()).isEqualTo("responses");
        assertThat(probe.getValue().getBaseUrl()).isEqualTo("https://8.8.8.8");   // 尾斜杠已去
        assertThat(probe.getValue().getModel()).isEqualTo("gpt-5-pro");
        assertThat(new ApiKeyCrypto(SECRET).decrypt(probe.getValue().getApiKeyEnc())).isEqualTo("sk-abcd1234");
        // 哨兵 id：TraderModelFactory 按 id 缓存，探针拿 userId 当 id 会和别人的 trader 撞车
        assertThat(probe.getValue().getId()).isEqualTo(-1L);
    }

    /** 认不出的协议直接拒，不能存进去等 ChatModelFactory 悄悄按 openai 发请求 */
    @Test
    void 非法协议被拒绝() {
        String err = service.save(1L, new UserLlmConfigService.SaveReq(
                "anthropic", "https://8.8.8.8", "gpt-5", null, null, "sk-x"));

        assertThat(err).isEqualTo("协议仅支持 openai / responses");
    }

    /**
     * 思考档位落库并抹平大小写空格。这条钉的是整条链路的第一段：档位存不进去，
     * 后面 ChatModelFactory 注入得再对也没用。
     */
    @Test
    void 思考档位归一化后入库() {
        when(mapper.selectById(1L)).thenReturn(null);
        AtomicReference<UserLlmConfig> inserted = new AtomicReference<>();
        when(mapper.insert(any(UserLlmConfig.class))).thenAnswer(inv -> {
            inserted.set(inv.getArgument(0));
            return 1;
        });

        assertThat(service.save(1L, new UserLlmConfigService.SaveReq(
                "openai", "https://8.8.8.8", "gpt-5", null, "  HIGH ", "sk-x"))).isNull();

        assertThat(inserted.get().getReasoningEffort()).isEqualTo("high");
    }

    /** 留空=不传，走模型默认。不能存成 "" ——那会被原样塞进上游请求体 */
    @Test
    void 思考档位留空存成null() {
        when(mapper.selectById(1L)).thenReturn(null);
        AtomicReference<UserLlmConfig> inserted = new AtomicReference<>();
        when(mapper.insert(any(UserLlmConfig.class))).thenAnswer(inv -> {
            inserted.set(inv.getArgument(0));
            return 1;
        });

        assertThat(service.save(1L, new UserLlmConfigService.SaveReq(
                "openai", "https://8.8.8.8", "gpt-5", null, "   ", "sk-x"))).isNull();

        assertThat(inserted.get().getReasoningEffort()).isNull();
    }

    /**
     * 认不出的档位当场拒。它是原样塞进上游请求体的，OpenAI 官方对不认识的值直接 400——
     * 那时用户看到的是一次失败的对话，而不是一句"档位填错了"。
     */
    @Test
    void 非法思考档位被拒绝() {
        String err = service.save(1L, new UserLlmConfigService.SaveReq(
                "openai", "https://8.8.8.8", "gpt-5", null, "ultra", "sk-x"));

        assertThat(err).contains("思考档位");
        verify(mapper, never()).insert(any(UserLlmConfig.class));
    }

    /**
     * 协议脏值必须在入库前抹平：下游 AiProtocols.isResponses 不 trim，
     * "responses " 存进去会被当成 openai，用户选了 responses 却发 /chat/completions
     */
    @Test
    void 协议大小写与空格入库前归一化() {
        when(mapper.selectById(1L)).thenReturn(null);
        AtomicReference<UserLlmConfig> inserted = new AtomicReference<>();
        when(mapper.insert(any(UserLlmConfig.class))).thenAnswer(inv -> {
            inserted.set(inv.getArgument(0));
            return 1;
        });

        assertThat(service.save(1L, new UserLlmConfigService.SaveReq(
                "  RESPONSES  ", "https://8.8.8.8", "gpt-5", null, null, "sk-x"))).isNull();

        assertThat(inserted.get().getApiProtocol()).isEqualTo("responses");
    }

    /** 没配过的用户拿到 null，准入层据此给"去配置"的引导（而不是当成系统错误） */
    @Test
    void 未配置时返回null() {
        when(mapper.selectById(9L)).thenReturn(null);

        assertThat(service.get(9L)).isNull();
    }
}
