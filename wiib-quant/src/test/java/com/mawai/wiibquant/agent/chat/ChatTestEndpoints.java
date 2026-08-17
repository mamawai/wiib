package com.mawai.wiibquant.agent.chat;

import com.mawai.wiibcommon.entity.UserLlmEndpoint;
import com.mawai.wiibquant.agent.llm.ChatEndpoints;

/** 对话测试共用：拼一份最小可用的端点/端点对（指纹要读的字段都填上） */
final class ChatTestEndpoints {

    private ChatTestEndpoints() {
    }

    static UserLlmEndpoint endpoint(String model) {
        return endpoint(model, "enc-key");
    }

    static UserLlmEndpoint endpoint(String model, String apiKeyEnc) {
        UserLlmEndpoint e = new UserLlmEndpoint();
        e.setId(1L);
        e.setUserId(1L);
        e.setName(model);
        e.setApiProtocol("openai");
        e.setBaseUrl("https://api.example.com");
        e.setModel(model);
        e.setApiKeyEnc(apiKeyEnc);
        return e;
    }

    /** 只有主模型（轻模型复用主模型） */
    static ChatEndpoints eps(long userId, String model) {
        return new ChatEndpoints(userId, endpoint(model), null);
    }
}
