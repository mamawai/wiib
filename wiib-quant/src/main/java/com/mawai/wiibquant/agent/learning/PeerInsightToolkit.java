package com.mawai.wiibquant.agent.learning;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

/**
 * 同侪查询工具（非 Spring bean）：每次学习会话 new 一个，构造器绑定"我是谁"。
 * 单工具双模式——传 traderId 深看某人，不传回排行榜。
 * 全只读：learning 不碰账本、不改计划、不写任何人的数据，包括看的人自己的。
 * <p>
 * 绑定 selfTraderId 是为了排行榜能标出自己那行——不标，模型会把自己的战绩当外人的经验学一遍。
 */
@Slf4j
public class PeerInsightToolkit {

    private final PeerInsightService peerInsightService;
    private final long selfTraderId;

    public PeerInsightToolkit(PeerInsightService peerInsightService, long selfTraderId) {
        this.peerInsightService = peerInsightService;
        this.selfTraderId = selfTraderId;
    }

    // 描述只说"能查到什么"，不下"应该多查/少查"的行为指令：看谁、看几个、看多深
    // 是系统提示词交给模型的开放决策，工具描述里再插一句就是两条指令打架
    @Tool(name = "peer_insights", description = """
            查看同侪（只读）。两种模式：
            - 传 traderId：深看这一个人——他的最新复盘全文、他的学习笔记、当前在场计划的论点与\
            失效条件、最近几笔已了结交易的「论点 → 结局」配对。
            - 不传 traderId：返回本局排行榜快照（每人：名字/状态/本局收益率/已了结笔数/最新复盘一句话）。\
            开场消息里已经给过你一份，需要重新核对时再调。
            排行榜每行开头 [id=N] 里的 N，就是这里要传的 traderId。""")
    public String peerInsights(
            @ToolParam(required = false,
                    description = "要深看的同侪 traderId（取自排行榜的 [id=N]）；不传则返回排行榜快照")
            Long traderId) {
        if (traderId == null) {
            log.info("[PeerTool] peer_insights 取排行榜 self={}", selfTraderId);
            return peerInsightService.leaderboard(selfTraderId);
        }
        log.info("[PeerTool] peer_insights 深看 traderId={} self={}", traderId, selfTraderId);
        return peerInsightService.detail(traderId);
    }
}
