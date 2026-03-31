package com.mawai.wiibservice.task;

import com.mawai.wiibcommon.entity.QuantForecastCycle;
import com.mawai.wiibcommon.entity.QuantForecastVerification;
import com.mawai.wiibcommon.entity.QuantHorizonForecast;
import com.mawai.wiibcommon.entity.QuantReflectionMemory;
import com.mawai.wiibcommon.util.JsonUtils;
import com.mawai.wiibservice.agent.quant.memory.VerificationService;
import com.mawai.wiibservice.mapper.QuantForecastCycleMapper;
import com.mawai.wiibservice.mapper.QuantForecastVerificationMapper;
import com.mawai.wiibservice.mapper.QuantHorizonForecastMapper;
import com.mawai.wiibservice.mapper.QuantReflectionMemoryMapper;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
public class ReflectionTask {

    private final ChatClient chatClient;
    private final QuantForecastCycleMapper cycleMapper;
    private final QuantHorizonForecastMapper horizonMapper;
    private final QuantForecastVerificationMapper verificationMapper;
    private final QuantReflectionMemoryMapper reflectionMapper;
    private final VerificationService verificationService;

    public ReflectionTask(ChatModel chatModel,
                          QuantForecastCycleMapper cycleMapper,
                          QuantHorizonForecastMapper horizonMapper,
                          QuantForecastVerificationMapper verificationMapper,
                          QuantReflectionMemoryMapper reflectionMapper,
                          VerificationService verificationService) {
        this.chatClient = ChatClient.builder(chatModel).build();
        this.cycleMapper = cycleMapper;
        this.horizonMapper = horizonMapper;
        this.verificationMapper = verificationMapper;
        this.reflectionMapper = reflectionMapper;
        this.verificationService = verificationService;
    }

    private static final List<String> WATCH_LIST = List.of("BTCUSDT");

    /**
     * 每6小时执行：批量验证 + LLM反思 + 写入记忆
     */
    @Scheduled(cron = "0 0 */6 * * *")
    public void reflectAndLearn() {
        for (String symbol : WATCH_LIST) {
            Thread.startVirtualThread(() -> {
                try {
                    runReflection(symbol);
                } catch (Exception e) {
                    log.error("[Reflect] 反思任务异常 symbol={}", symbol, e);
                }
            });
        }
    }

    private void runReflection(String symbol) {
        // 1. 验证未验证的周期（最多24个，约12小时的量）
        List<QuantForecastCycle> unverified = cycleMapper.selectUnverified(symbol, 24);
        if (unverified.isEmpty()) {
            log.info("[Reflect] 无待验证周期 symbol={}", symbol);
            return;
        }

        int verifiedCount = 0;
        for (QuantForecastCycle cycle : unverified) {
            List<QuantHorizonForecast> forecasts = horizonMapper.selectByCycleId(cycle.getCycleId());
            if (!forecasts.isEmpty()) {
                verificationService.verifyCycle(cycle, forecasts);
                verifiedCount++;
            }
        }
        log.info("[Reflect] 验证完成 symbol={} verified={}/{}", symbol, verifiedCount, unverified.size());

        // 2. 收集最近验证结果用于反思
        List<QuantForecastVerification> recentVerifications = verificationMapper.selectRecent(symbol, 36);
        if (recentVerifications.size() < 3) {
            log.info("[Reflect] 验证样本不足({}条)，跳过反思", recentVerifications.size());
            return;
        }

        // 3. LLM反思
        String reflectionPrompt = buildReflectionPrompt(recentVerifications, symbol);
        String llmResponse;
        try {
            llmResponse = chatClient.prompt().user(reflectionPrompt).call().content();
        } catch (Exception e) {
            log.warn("[Reflect] LLM反思调用失败: {}", e.getMessage());
            return;
        }

        // 4. 解析反思结果并写入记忆
        saveReflection(llmResponse, recentVerifications, symbol);

        // 5. 清理30天前的旧记忆
        reflectionMapper.cleanupOld();
    }

    private String buildReflectionPrompt(List<QuantForecastVerification> verifications, String symbol) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是量化系统复盘分析师。以下是").append(symbol).append("最近的预测验证结果。\n\n");
        sb.append("【预测验证】\n");

        int correct = 0, total = 0;
        for (QuantForecastVerification v : verifications) {
            total++;
            if (v.getPredictionCorrect() != null && v.getPredictionCorrect()) correct++;
            sb.append(total).append(". ")
                    .append(v.getHorizon()).append(" ")
                    .append("预测=").append(v.getPredictedDirection())
                    .append(" conf=").append(v.getPredictedConfidence())
                    .append(" 实际=").append(v.getActualChangeBps() >= 0 ? "+" : "").append(v.getActualChangeBps()).append("bps")
                    .append(v.getPredictionCorrect() != null && v.getPredictionCorrect() ? " ✓" : " ✗")
                    .append("\n");
        }
        sb.append("\n总命中率: ").append(correct).append("/").append(total)
                .append(" (").append(total > 0 ? correct * 100 / total : 0).append("%)\n\n");

        sb.append("""
                请分析：
                1. 哪些情况下预测准确/不准确？（按方向、区间维度分析）
                2. 是否存在系统性偏差？（如总是偏多/偏空）
                3. confidence高时准确率是否更高？
                4. 给出2-3条改进建议

                严格返回JSON（不要markdown包裹）：
                {
                  "overallAccuracy": 0.65,
                  "biases": ["偏差描述"],
                  "lessons": [
                    {"tag": "标签如RANGE_BULLISH_BIAS", "lesson": "具体教训，50字内"}
                  ]
                }
                """);
        return sb.toString();
    }

    private void saveReflection(String llmResponse, List<QuantForecastVerification> verifications, String symbol) {
        try {
            String json = JsonUtils.extractJson(llmResponse);
            JSONObject root = JSON.parseObject(json);
            JSONArray lessons = root.getJSONArray("lessons");
            if (lessons == null || lessons.isEmpty()) return;

            // 取最近一条验证的cycle信息来关联
            QuantForecastVerification latest = verifications.get(0);
            // 查对应cycle获取regime
            QuantForecastCycle cycle = cycleMapper.selectLatest(symbol);
            String regime = "UNKNOWN";
            if (cycle != null && cycle.getSnapshotJson() != null) {
                try {
                    JSONObject snap = JSON.parseObject(cycle.getSnapshotJson());
                    regime = snap.getString("regime") != null ? snap.getString("regime") : "UNKNOWN";
                } catch (Exception ignored) {}
            }

            StringBuilder allTags = new StringBuilder();
            StringBuilder allLessons = new StringBuilder();
            for (int i = 0; i < lessons.size(); i++) {
                JSONObject lesson = lessons.getJSONObject(i);
                String tag = lesson.getString("tag");
                String text = lesson.getString("lesson");
                if (tag != null) allTags.append(tag).append(",");
                if (text != null) allLessons.append(text).append(" ");
            }

            QuantReflectionMemory memory = new QuantReflectionMemory();
            memory.setSymbol(symbol);
            memory.setCycleId(latest.getCycleId());
            memory.setRegime(regime);
            memory.setOverallDecision(cycle != null ? cycle.getOverallDecision() : null);
            memory.setPredictedDirection(latest.getPredictedDirection());
            memory.setActualPriceChangeBps(latest.getActualChangeBps());
            memory.setPredictionCorrect(latest.getPredictionCorrect());
            memory.setReflectionText(allLessons.toString().trim());
            memory.setLessonTags(allTags.length() > 0 ? allTags.substring(0, allTags.length() - 1) : null);
            reflectionMapper.insert(memory);

            log.info("[Reflect] 反思记忆写入成功 symbol={} tags={}", symbol, memory.getLessonTags());
        } catch (Exception e) {
            log.warn("[Reflect] 反思结果解析失败: {}", e.getMessage());
        }
    }
}
