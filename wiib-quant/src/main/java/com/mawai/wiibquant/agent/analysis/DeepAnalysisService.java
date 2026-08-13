package com.mawai.wiibquant.agent.analysis;

import com.alibaba.fastjson2.JSONObject;
import com.mawai.wiibcommon.entity.QuantDeepAnalysis;
import com.mawai.wiibcommon.util.JsonUtils;
import com.mawai.wiibquant.agent.quant.domain.FeatureSnapshot;
import com.mawai.wiibquant.agent.quant.domain.news.NewsFlash;
import com.mawai.wiibquant.agent.toolkit.MarketAssembly;
import com.mawai.wiibquant.agent.toolkit.MarketDataService;
import com.mawai.wiibquant.agent.toolkit.NewsCache;
import com.mawai.wiibquant.mapper.QuantDeepAnalysisMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 深研判服务（P2b）：新闻拼接 → Bull∥Bear 对抗辩论 → Judge 裁决 → 落库。
 * 产物是研判叙事（方向倾向/情景分布/失效条件，证据实在均衡才标无方向态），与交易解耦；
 * LLM 任一步失败只缺席本次研判。
 * 数据上下文只用实时测量（快照/vol预测/脆弱度已随预测管线下线，2026-08）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DeepAnalysisService {

    private static final BeanOutputConverter<DeepAnalysisResponse> JUDGE_CONVERTER =
            new BeanOutputConverter<>(DeepAnalysisResponse.class);

    private final MarketDataService marketDataService;
    private final NewsCache newsCache;
    private final QuantDeepAnalysisMapper mapper;

    /** 新闻上下文：缓存的重要快讯原样拼成文本喂辩论（不再 LLM 浓缩）；无则"无新闻上下文"。 */
    public String buildNewsContext() {
        List<NewsFlash> flashes = newsCache.getFlashes();
        if (flashes.isEmpty()) {
            return "无新闻上下文";
        }
        StringBuilder sb = new StringBuilder();
        for (NewsFlash f : flashes) {
            sb.append("· ").append(f.title());
            String body = f.plainContent();
            if (!body.isBlank()) {
                sb.append("：").append(body);
            }
            sb.append('\n');
        }
        return sb.toString().trim();
    }

    /**
     * 模型由调用方传入而不是自己去拿：BYOK 后每个用户的模型不同，
     * 服务自己去 runtimeManager 取就不知道"当前是谁在用"了。
     */
    private String call(ChatModel model, String prompt) {
        return ChatClient.builder(model).build().prompt().user(prompt).call().content();
    }

    /** Bull 辩手：严格做多立场；失败给占位论据不阻断。 */
    public String bullArgue(ChatModel model, String symbol, String newsContext) {
        return argue(model, symbol, newsContext, true);
    }

    /** Bear 辩手：严格做空/观望立场；失败给占位论据不阻断。 */
    public String bearArgue(ChatModel model, String symbol, String newsContext) {
        return argue(model, symbol, newsContext, false);
    }

    private String argue(ChatModel model, String symbol, String newsContext, boolean bull) {
        String side = bull ? "Bull" : "Bear";
        try {
            String stance = bull
                    ? """
                    你是加密货币研判系统的做多辩手(Bull)。基于以下数据，严格站在"未来6-24小时偏多"立场构建最强论据。
                    - 你的唯一目标是论证偏多情景，不要替对方辩护，不要自我质疑
                    - 引用具体信号数据（如"funding偏离+0.35但清算压力显示空头爆仓"）
                    - 指出有利于多头的持仓结构、资金费、微结构信号"""
                    : """
                    你是加密货币研判系统的做空辩手(Bear)。基于以下数据，严格站在"未来6-24小时偏空或震荡"立场构建最强论据。
                    - 你的唯一目标是论证偏空/震荡情景，不要替对方辩护，不要自我质疑
                    - 引用具体信号数据（如"多头拥挤 lsrExtreme=0.4 且下方清算密集"）
                    - 指出资金费率、持仓量、爆仓信号中不利于多头的部分""";
            String prompt = """
                    %s

                    %s

                    限300字，纯文字论述，不要返回JSON。""".formatted(stance, buildDataContext(symbol, newsContext));
            String argument = call(model, prompt);
            return argument != null && !argument.isBlank() ? argument : side + "辩手未能提供论据";
        } catch (Exception e) {
            log.warn("[Deep] {}辩手失败 symbol={} msg={}", side, symbol, e.getMessage());
            return side + "辩手未能提供论据";
        }
    }

    /** Judge 裁决：综合数据+双方论据产研判；失败返回 null（本次研判缺席）。 */
    public QuantDeepAnalysis judge(ChatModel model, String symbol, long closeTime, String triggerSource,
                                   String newsContext, String bullArgument, String bearArgument) {
        try {
            String prompt = """
                    你是加密货币研判系统的裁判(Judge)。Bull 与 Bear 辩手已在完全隔离的环境中独立完成辩论。
                    请综合原始数据与双方论据，产出一份"市场研判"——把信号综合成可读、可证伪的情景研判，
                    并给出明确的涨跌方向判断：哪方证据更硬就旗帜鲜明地偏向哪方，
                    概率分布要拉开差距体现你的倾向，不要为了显得中立把三情景摊平。

                    ========== 原始数据 ==========
                    %s

                    ========== 辩论论据 ==========
                    【Bull辩手（做多方）】
                    %s

                    【Bear辩手（做空/观望方）】
                    %s

                    ========== 产出要求 ==========
                    1. narrative：一段研判叙事(150字内)——先亮明方向观点，再给"若X兑现 → 未来Yh可能Z"的后果式人话
                    2. bullPct/rangePct/bearPct：未来6-24h三情景概率，和必须等于100；按证据强弱拉开差距，
                       体现你真实的方向倾向，不要习惯性输出接近均匀的分布
                    3. noDirection：仅当多空证据确实势均力敌、给不出任何倾向时才设 true——这是例外不是默认
                    4. invalidation：一句话反事实失效条件，必须可证伪，"若A则本研判作废"
                    5. judgeReasoning：裁决推理(100字内)——谁的证据更具体、更有数据支撑

                    %s
                    """.formatted(buildDataContext(symbol, newsContext), bullArgument, bearArgument,
                    JUDGE_CONVERTER.getFormat());
            String response = call(model, prompt);
            if (response == null || response.isBlank()) {
                log.warn("[Deep] Judge 空响应 symbol={}", symbol);
                return null;
            }
            DeepAnalysisResponse parsed = JUDGE_CONVERTER.convert(JsonUtils.extractJson(response));
            return toEntity(symbol, closeTime, triggerSource, newsContext, bullArgument, bearArgument, parsed);
        } catch (Exception e) {
            log.warn("[Deep] Judge 失败 symbol={} msg={}", symbol, e.getMessage());
            return null;
        }
    }

    public Long persist(QuantDeepAnalysis analysis) {
        mapper.insert(analysis);
        return analysis.getId();
    }

    /** 数据上下文：实时微结构 + 期权IV + 新闻；重对象来自共享组装（60s 缓存）。 */
    private String buildDataContext(String symbol, String newsContext) {
        MarketAssembly a = marketDataService.assemble(symbol);
        if (!a.available()) {
            return "【市场数据】暂不可用\n【新闻上下文】" + newsContext;
        }
        FeatureSnapshot s = a.snapshot();
        String micro = ("futuresBidAsk=%.3f tradeDelta=%.3f largeBias=%.3f oiChange=%.3f fundingDev=%.3f "
                + "lsrExtreme=%.3f liquidationPressure=%.3f(vol=%.0fUSDT) topTraderBias=%.3f takerPressure=%.3f "
                + "fearGreed=%d(%s)").formatted(
                s.bidAskImbalance(), s.tradeDelta(), s.largeTradeBias(), s.oiChangeRate(), s.fundingDeviation(),
                s.lsrExtreme(), s.liquidationPressure(), s.liquidationVolumeUsdt(),
                s.topTraderBias(), s.takerBuySellPressure(), s.fearGreedIndex(), s.fearGreedLabel());
        String iv = s.toIvSummary();
        return """
                【标的】%s 现价=%s
                【微结构快照】%s
                【期权IV】%s
                【新闻上下文】
                %s""".formatted(s.symbol(), s.lastPrice(), micro, iv, newsContext);
    }

    private QuantDeepAnalysis toEntity(String symbol, long closeTime, String triggerSource,
                                       String newsContext, String bull, String bear, DeepAnalysisResponse r) {
        // 情景分布归一化到 100（LLM 偶尔差 1-3）
        JSONObject scenarios = getScenarios(r);

        QuantDeepAnalysis entity = new QuantDeepAnalysis();
        entity.setSymbol(symbol);
        entity.setCloseTime(closeTime);
        entity.setTriggerSource(triggerSource);
        entity.setNarrative(r.narrative());
        entity.setScenariosJson(scenarios.toJSONString());
        entity.setNoDirection(Boolean.TRUE.equals(r.noDirection()));
        entity.setInvalidation(r.invalidation());
        entity.setBullArgument(bull);
        entity.setBearArgument(bear);
        entity.setJudgeReasoning(r.judgeReasoning());
        entity.setNewsContext(newsContext);
        entity.setCreatedAt(LocalDateTime.now());
        return entity;
    }

    private static @NonNull JSONObject getScenarios(DeepAnalysisResponse r) {
        int bullPct = r.bullPct() != null ? Math.max(0, r.bullPct()) : 33;
        int rangePct = r.rangePct() != null ? Math.max(0, r.rangePct()) : 34;
        int bearPct = r.bearPct() != null ? Math.max(0, r.bearPct()) : 33;
        int sum = bullPct + rangePct + bearPct;
        if (sum > 0 && sum != 100) {
            rangePct += 100 - sum;
        }
        JSONObject scenarios = new JSONObject();
        scenarios.put("bullPct", bullPct);
        scenarios.put("rangePct", rangePct);
        scenarios.put("bearPct", bearPct);
        return scenarios;
    }

}
