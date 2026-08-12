package com.mawai.wiibquant.agent.chat;

import com.mawai.wiibquant.agent.trader.TraderChatService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

/**
 * 对 trader 动手的工具集，挂在 summarizer 叶子上（与 {@link DeepAnalysisToolkit} 并列）。
 * <p>
 * <b>为什么挂汇总者而不是挂 trader 专家</b>：HITL 闸门 {@link ApprovalGate} 装在 summarizer 的
 * 工具边上，动作必须落在它的管辖范围内才拦得住；而且"唤醒/复盘/留言"是替用户完成请求的收尾动作，
 * 本就该由写最终回答的那个人去做。查询归专家，动手归汇总者。
 * <p>
 * <b>这里同样没有 HITL 判断</b>（理由同 DeepAnalysisToolkit）：闸门才同时看得到 sessionId 和
 * tool_call。前两个工具受闸门管辖，方法体被执行时就意味着用户已经点过头了。
 * <p>
 * userId 建叶子时烤死，理由见 {@link TraderQueryToolkit}。
 */
@Slf4j
public class TraderActionToolkit {

    private final TraderChatService traderChatService;
    private final WorkbenchRunRegistry runRegistry;
    private final long userId;

    public TraderActionToolkit(TraderChatService traderChatService, WorkbenchRunRegistry runRegistry,
                               long userId) {
        this.traderChatService = traderChatService;
        this.runRegistry = runRegistry;
        this.userId = userId;
    }

    @Tool(name = "wake_trader", description = """
            Wake the user's AI trader right now for one extra decision round, on top of its schedule.
            EXPENSIVE AND REAL: it burns the trader's own model budget and MAY OPEN OR CLOSE POSITIONS.
            Requires user approval. Only call when the user explicitly asks to wake / run the trader now.
            If the result status is PENDING_APPROVAL, tell the user a confirmation card is waiting;
            after they approve you will be called again to actually execute.
            Runs in the background - it returns as soon as the round is triggered, not when it finishes.""")
    public String wakeTrader() {
        log.info("[TraderAction] 手动唤醒 userId={}", userId);
        return traderChatService.wake(userId);
    }

    @Tool(name = "review_trader_now", description = """
            Run the daily retrospective for the user's AI trader right now instead of waiting for the
            daily boundary: it re-reads the closed trades, writes a REVIEW entry and rewrites the
            trader's memory notes. EXPENSIVE: costs one deep-model call. Requires user approval.
            If the result status is PENDING_APPROVAL, tell the user a confirmation card is waiting.
            Skips itself (and costs nothing) when there is no newly closed trade to review.""")
    public String reviewTraderNow() {
        String sessionId = ToolRunContext.sessionId();
        log.info("[TraderAction] 点播复盘 userId={} session={}", userId, sessionId);
        // 复盘是同步的、最长 180s，期间 SSE 通道一个字节都没有——不推进度前端就是干等
        progress(sessionId, "正在复盘 trader 的近期交易（约需 1~3 分钟）");
        String result = traderChatService.reviewNow(userId);
        progress(sessionId, "复盘结束，正在生成回答");
        return result;
    }

    @Tool(name = "leave_note_to_trader", description = """
            Leave a short one-off note for the user's AI trader. The trader will see it in its system
            prompt on its NEXT wake-up, exactly once, and then it is erased - it is a passing remark,
            not a standing rule (standing rules belong in the trader's custom prompt on its config page).
            Cheap, no approval needed. Writing a new note overwrites any note not yet read.""")
    public String leaveNoteToTrader(@ToolParam(description =
            "The note in the user's own words, <=500 chars, e.g. 今晚有 CPI 数据，仓位放轻一点") String note) {
        log.info("[TraderAction] 留言 userId={}", userId);
        return traderChatService.leaveNote(userId, note);
    }

    /** 进度尽力而为：拿不到会话号（不在工具执行栈里）就静默跳过，不影响正确性。 */
    private void progress(String sessionId, String text) {
        if (sessionId != null) {
            runRegistry.publishProgress(sessionId, text);
        }
    }
}
