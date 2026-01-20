package com.mawai.wiibservice.service.impl;

import cn.hutool.http.HttpRequest;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.mawai.wiibservice.config.AiModelConfig;
import com.mawai.wiibservice.service.AiService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;

/**
 * AI调用服务实现
 * 多模型优先级重试机制，确保生成可靠性
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiServiceImpl implements AiService {

    private final AiModelConfig aiModelConfig;

    /**
     * 调用AI（按优先级重试）
     * 流程：按priority排序 -> 依次尝试 -> 失败则尝试下一个
     */
    @Override
    public String chat(String prompt) {
        // 获取启用的提供商，按优先级排序
        List<AiModelConfig.ModelProvider> enabledProviders = aiModelConfig.getProviders().stream()
                .filter(AiModelConfig.ModelProvider::getEnabled)
                .sorted(Comparator.comparing(AiModelConfig.ModelProvider::getPriority))
                .toList();

        if (enabledProviders.isEmpty()) {
            log.error("没有启用的AI提供商");
            throw new RuntimeException("没有启用的AI提供商");
        }

        // 按优先级依次尝试
        Exception lastException = null;
        for (AiModelConfig.ModelProvider provider : enabledProviders) {
            try {
                log.info("尝试调用AI: {} (priority={})", provider.getName(), provider.getPriority());
                String result = callAiWithRetry(provider, prompt);
                log.info("AI调用成功: {}", provider.getName());
                return result;
            } catch (Exception e) {
                log.warn("AI调用失败: {} - {}", provider.getName(), e.getMessage());
                lastException = e;
                // 继续尝试下一个提供商
            }
        }

        // 所有提供商都失败
        log.error("所有AI提供商调用失败");
        throw new RuntimeException("所有AI提供商调用失败", lastException);
    }

    /**
     * 调用指定提供商
     */
    @Override
    public String chatWithProvider(String providerName, String prompt) {
        AiModelConfig.ModelProvider provider = aiModelConfig.getProviders().stream()
                .filter(p -> p.getName().equals(providerName) && p.getEnabled())
                .findFirst()
                .orElseThrow(() -> new RuntimeException("提供商不存在或未启用: " + providerName));

        return callAiWithRetry(provider, prompt);
    }

    /**
     * 调用AI（带重试）
     */
    private String callAiWithRetry(AiModelConfig.ModelProvider provider, String prompt) {
        int maxRetries = provider.getMaxRetries();
        Exception lastException = null;

        for (int i = 0; i <= maxRetries; i++) {
            try {
                return callAi(provider, prompt);
            } catch (Exception e) {
                lastException = e;
                if (i < maxRetries) {
                    log.warn("AI调用失败，重试 {}/{}: {}", i + 1, maxRetries, e.getMessage());
                    // 重试延迟
                    try {
                        Thread.sleep(1000 * (i + 1)); // 递增延迟
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
        }

        throw new RuntimeException("AI调用失败，已重试" + maxRetries + "次", lastException);
    }

    /**
     * 实际调用AI（OpenAI Compatible API）
     */
    private String callAi(AiModelConfig.ModelProvider provider, String prompt) {
        // 构建请求体
        JSONObject requestBody = new JSONObject();
        requestBody.set("model", provider.getModel());

        JSONArray messages = new JSONArray();
        JSONObject message = new JSONObject();
        message.set("role", "user");
        message.set("content", prompt);
        messages.add(message);
        requestBody.set("messages", messages);

        // 发送请求
        String response = HttpRequest.post(provider.getBaseUrl() + "/chat/completions")
                .header("Authorization", "Bearer " + provider.getApiKey())
                .header("Content-Type", "application/json")
                .body(requestBody.toString())
                .timeout(provider.getTimeout())
                .execute()
                .body();

        // 解析响应
        JSONObject json = JSONUtil.parseObj(response);

        // 检查错误
        if (json.containsKey("error")) {
            String errorMsg = json.getByPath("error.message", String.class);
            throw new RuntimeException("AI返回错误: " + errorMsg);
        }

        // 提取内容
        String content = json.getByPath("choices[0].message.content", String.class);
        if (content == null || content.trim().isEmpty()) {
            throw new RuntimeException("AI返回内容为空");
        }

        return content;
    }
}
