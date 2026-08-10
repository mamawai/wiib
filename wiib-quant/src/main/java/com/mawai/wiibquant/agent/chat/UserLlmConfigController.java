package com.mawai.wiibquant.agent.chat;

import com.mawai.wiibcommon.annotation.CurrentUserId;
import com.mawai.wiibcommon.entity.UserLlmConfig;
import com.mawai.wiibcommon.util.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 用户 BYOK 端点配置。研判工作台对话用自己的 key，平台不承担 LLM 成本。 */
@Slf4j
@Tag(name = "用户 LLM 配置")
@RestController
@RequestMapping("/api/ai/llm-config")
@RequiredArgsConstructor
public class UserLlmConfigController {

    private final UserLlmConfigService service;

    /** key 只回尾 4 位，明文任何情况下不出服务端 */
    public record ConfigView(String apiProtocol, String baseUrl, String model,
                             String lightModel, String apiKeyTail) {
    }

    public record SaveRequest(String apiProtocol, String baseUrl, String model,
                              String lightModel, String apiKey) {
        UserLlmConfigService.SaveReq toReq() {
            return new UserLlmConfigService.SaveReq(apiProtocol, baseUrl, model, lightModel, apiKey);
        }
    }

    @GetMapping("/mine")
    @Operation(summary = "我的 LLM 配置（key 只回尾4位）")
    public Result<ConfigView> mine(@CurrentUserId long userId) {
        UserLlmConfig c = service.get(userId);
        if (c == null) {
            return Result.ok(null);
        }
        return Result.ok(new ConfigView(c.getApiProtocol(), c.getBaseUrl(), c.getModel(),
                c.getLightModel(), service.keyTail(c)));
    }

    @PostMapping
    @Operation(summary = "保存配置（apiKey 传空=不换）")
    public Result<Void> save(@CurrentUserId long userId, @RequestBody SaveRequest req) {
        String err = service.save(userId, req.toReq());
        return err == null ? Result.ok(null) : Result.fail(err);
    }

    @PostMapping("/models")
    @Operation(summary = "拉取端点可用模型清单")
    public Result<List<String>> listModels(@CurrentUserId long userId, @RequestBody SaveRequest req) {
        UserLlmConfigService.ListModelsResult r = service.listModels(userId, req.toReq());
        return r.error() == null ? Result.ok(r.models()) : Result.fail(r.error());
    }

    /** 探测独立成端点而不是塞进 save：保存不该被端点抖动挡住，也不该每次都烧一次 token */
    @PostMapping("/test")
    @Operation(summary = "测试端点连通性（apiKey 传空=用已存的）")
    public Result<Void> test(@CurrentUserId long userId, @RequestBody SaveRequest req) {
        String err = service.testConnection(userId, req.toReq());
        return err == null ? Result.ok(null) : Result.fail(err);
    }
}
