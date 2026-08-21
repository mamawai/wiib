package com.mawai.wiibquant.agent.behavior;

import com.mawai.wiibcommon.enums.AgentLang;
import com.mawai.wiibquant.agent.behavior.BehaviorDataCollector.Section;
import com.mawai.wiibquant.agent.i18n.PromptCatalog;
import com.mawai.wiibquant.agent.llm.ResilientChatService;
import lombok.RequiredArgsConstructor;
import org.bsc.langgraph4j.prebuilt.MessagesState;
import org.bsc.langgraph4j.spring.ai.agent.ReactAgent;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.util.json.schema.JsonSchemaGenerator;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 行为分析 workflow：并发拉齐 10 段数据 → 拼一个 prompt → 调一次 LLM，返回模型原文。
 * <p>为什么不是 ReAct agent：10 个端点的入参只有 userId、路径写死，模型在"查什么"上没有决策自由度，
 * 唯一正确的行为就是全查一遍。配个循环只是让它来回跑腿（且每轮要把之前所有工具结果重发一遍），
 * 换成一次性给全，模型调用固定 1 次、数据请求可以并发、也不可能撞框架的迭代硬顶。
 * <p>提示词整套（系统指令 / 开场白 / 段标题）都在 {@link PromptCatalog}，按用户的 {@link AgentLang}
 * 取——中文用户拿中文报告，英文用户拿英文报告，模型看到的每个字都是同一门语言。
 * <p>输出结构靠提示词里的 JSON Schema 约束（改造前后同一套），解析与校验在
 * {@link BehaviorAnalysisService}。
 */
@Component
@RequiredArgsConstructor
public class BehaviorAnalysisWorkflow {

    /** 从报告类自动生成，与语言无关（字段名而已），只生成一次 */
    private static final String OUTPUT_SCHEMA =
            JsonSchemaGenerator.generateForType(BehaviorAnalysisReport.class);

    private final BehaviorDataCollector collector;
    private final PromptCatalog prompts;

    /**
     * 跑完整个 workflow，返回模型原文（可能带 ```json 围栏，调用方按老规矩 extractJson）。
     *
     * @param chatModel  由调用方给：现在唯一入口是对话轨的工具，用的是这个用户 BYOK 的深模型
     * @param onProgress 阶段文案（已按 lang 取好词），推给对话的 SSE 通道让用户看见进度；可空
     */
    public String run(ChatModel chatModel, long userId, AgentLang lang, Consumer<String> onProgress) {
        List<Section> sections = collector.collect(userId, (done, total) -> progress(onProgress,
                prompts.get(lang, "behavior.progress.collecting",
                        Map.of("done", done, "total", total))));
        // 数据齐了之后是一次大模型调用，静默数十秒——不报一声用户会以为卡死
        progress(onProgress, prompts.get(lang, "behavior.progress.analyzing"));

        String system = prompts.get(lang, "behavior.system", Map.of("schema", OUTPUT_SCHEMA));
        ChatResponse response = chatService(chatModel, system)
                .execute(List.of(new UserMessage(buildPrompt(lang, userId, sections))));

        Generation result = response.getResult();
        String text = result == null ? null : result.getOutput().getText();
        if (text == null || text.isBlank()) {
            throw new IllegalStateException("行为分析未返回有效内容");
        }
        return text;
    }

    /** 进度是尽力而为：没给回调就静默跳过，不影响正确性 */
    private static void progress(Consumer<String> onProgress, String text) {
        if (onProgress != null) {
            onProgress.accept(text);
        }
    }

    /**
     * 走 {@link ResilientChatService} 而不是裸 {@code chatModel.call}：兜底切换与"读响应被掐"的补救挂在这一层。
     * 它的工厂签名要一个 ReactAgentBuilder，这里只借它捎系统提示——不挂工具、不 build 图，
     * 所以没有 ReAct 循环，落到底就是一次 {@code model.call}。
     */
    private ReactAgent.ChatService chatService(ChatModel chatModel, String system) {
        return ResilientChatService.builder().model(chatModel).asFactory()
                .apply(ReactAgent.<MessagesState<Message>>builder().defaultSystem(system));
    }

    private String buildPrompt(AgentLang lang, long userId, List<Section> sections) {
        StringBuilder prompt = new StringBuilder(
                prompts.get(lang, "behavior.intro", Map.of("userId", userId)));
        for (Section section : sections) {
            prompt.append("\n## ").append(prompts.get(lang, "behavior.section." + section.endpoint()))
                    .append('\n').append(section.json()).append('\n');
        }
        return prompt.toString();
    }
}
