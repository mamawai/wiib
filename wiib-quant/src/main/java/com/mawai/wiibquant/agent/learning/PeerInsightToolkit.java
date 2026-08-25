package com.mawai.wiibquant.agent.learning;

import com.mawai.wiibcommon.enums.AgentLang;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

/**
 * 同侪查询工具（非 Spring bean）：每次学习会话 new 一个，构造器绑定"我是谁"。
 * 单工具双模式——传 traderId 深看某人，不传回排行榜。
 * 全只读：learning 不碰账本、不改计划、不写任何人的数据，包括看的人自己的。
 * <p>
 * 绑定 selfTraderId 是为了排行榜能标出自己那行——不标，模型会把自己的战绩当外人的经验学一遍。
 * <p>
 * 同时绑定 lang：查回来的排行榜/详情段名要跟<b>看的人</b>走。工具描述本身由
 * {@link com.mawai.wiibquant.agent.i18n.LocalizedToolCallbacks} 换成 {@code tool.peer_insights}
 * 那条，注解里这份只当词表缺失时的兜底。
 */
@Slf4j
public class PeerInsightToolkit {

    private final PeerInsightService peerInsightService;
    private final long selfTraderId;
    private final AgentLang lang;

    public PeerInsightToolkit(PeerInsightService peerInsightService, long selfTraderId, AgentLang lang) {
        this.peerInsightService = peerInsightService;
        this.selfTraderId = selfTraderId;
        this.lang = lang;
    }

    // 描述只说"能查到什么"，不下"应该多查/少查"的行为指令：看谁、看几个、看多深
    // 是系统提示词交给模型的开放决策，工具描述里再插一句就是两条指令打架
    @Tool(name = "peer_insights", description = """
            Look at peers (read-only). Pass traderId to inspect that one trader in depth;
            omit it to get this round's leaderboard snapshot instead.""")
    public String peerInsights(
            @ToolParam(required = false,
                    description = "traderId of the peer to inspect, i.e. the N in [id=N] on the leaderboard; "
                            + "omit it to get the leaderboard snapshot instead")
            Long traderId) {
        if (traderId == null) {
            log.info("[PeerTool] peer_insights 取排行榜 self={}", selfTraderId);
            return peerInsightService.leaderboard(selfTraderId, lang);
        }
        log.info("[PeerTool] peer_insights 深看 traderId={} self={}", traderId, selfTraderId);
        return peerInsightService.detail(traderId, lang);
    }
}
