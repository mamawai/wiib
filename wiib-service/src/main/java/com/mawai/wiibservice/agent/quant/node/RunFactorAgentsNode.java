package com.mawai.wiibservice.agent.quant.node;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import com.mawai.wiibservice.agent.quant.domain.AgentVote;
import com.mawai.wiibservice.agent.quant.domain.FeatureSnapshot;
import com.mawai.wiibservice.agent.quant.factor.FactorAgent;
import com.mawai.wiibservice.agent.quant.factor.NewsEventAgent;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * 5个因子Agent并行执行节点。
 * 内部使用虚拟线程并行，纯Java Agent超时30s，LLM Agent超时60s。
 */
@Slf4j
public class RunFactorAgentsNode implements NodeAction {

    private final List<FactorAgent> agents;

    public RunFactorAgentsNode(List<FactorAgent> agents) {
        this.agents = agents;
    }

    @Override
    public Map<String, Object> apply(OverAllState state) {
        long startMs = System.currentTimeMillis();
        FeatureSnapshot snapshot = (FeatureSnapshot) state.value("feature_snapshot").orElse(null);
        if (snapshot == null) {
            log.error("[Q3] feature_snapshot为空");
            return Map.of("agent_votes", List.of());
        }

        log.info("[Q3.0] run_factors开始 agents={} symbol={}", agents.size(), snapshot.symbol());
        List<AgentVote> allVotes = new ArrayList<>();

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<List<AgentVote>>> futures = new ArrayList<>(agents.size());

            for (FactorAgent agent : agents) {
                futures.add(executor.submit(() -> {
                    try {
                        long start = System.currentTimeMillis();
                        List<AgentVote> votes = agent.evaluate(snapshot);
                        log.info("[Q3.agent] {}完成 {}ms {}票",
                                agent.name(), System.currentTimeMillis() - start, votes.size());
                        return votes;
                    } catch (Exception e) {
                        log.warn("[Q3] Agent[{}] 执行失败: {}", agent.name(), e.getMessage());
                        return List.of(
                                AgentVote.noTrade(agent.name(), "0_10", "AGENT_ERROR"),
                                AgentVote.noTrade(agent.name(), "10_20", "AGENT_ERROR"),
                                AgentVote.noTrade(agent.name(), "20_30", "AGENT_ERROR"));
                    }
                }));
            }

            for (int i = 0; i < futures.size(); i++) {
                try {
                    // LLM Agent(news_event)给更长超时
                    int timeout = "news_event".equals(agents.get(i).name()) ? 60 : 30;
                    allVotes.addAll(futures.get(i).get(timeout, TimeUnit.SECONDS));
                } catch (Exception e) {
                    String name = agents.get(i).name();
                    log.warn("[Q3] Agent[{}] 超时/异常: {}", name, e.getMessage());
                    allVotes.add(AgentVote.noTrade(name, "0_10", "TIMEOUT"));
                    allVotes.add(AgentVote.noTrade(name, "10_20", "TIMEOUT"));
                    allVotes.add(AgentVote.noTrade(name, "20_30", "TIMEOUT"));
                }
            }
        }

        log.info("[Q3.end] run_factors完成 共{}票 耗时{}ms", allVotes.size(), System.currentTimeMillis() - startMs);

        // 提取LLM筛选后的新闻
        List<NewsEventAgent.FilteredNewsItem> filteredNews = List.of();
        for (FactorAgent agent : agents) {
            if (agent instanceof NewsEventAgent newsAgent) {
                filteredNews = newsAgent.getLastFilteredNews();
                break;
            }
        }
        log.info("[Q3.end] LLM筛选新闻={}条", filteredNews.size());

        Map<String, Object> result = new HashMap<>();
        result.put("agent_votes", allVotes);
        result.put("filtered_news", filteredNews);
        return result;
    }
}
