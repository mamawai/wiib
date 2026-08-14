package com.mawai.wiibquant.agent.behavior;

import com.mawai.wiibquant.agent.behavior.BehaviorDataCollector.Section;
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
import java.util.function.Consumer;

/**
 * 行为分析 workflow：并发拉齐 10 段数据 → 拼一个 prompt → 调一次 LLM，返回模型原文。
 * <p>为什么不是 ReAct agent：10 个端点的入参只有 userId、路径写死，模型在"查什么"上没有决策自由度，
 * 唯一正确的行为就是全查一遍。配个循环只是让它来回跑腿（且每轮要把之前所有工具结果重发一遍），
 * 换成一次性给全，模型调用固定 1 次、数据请求可以并发、也不可能撞框架的迭代硬顶。
 * <p>输出结构靠提示词里的 JSON Schema 约束（改造前后同一套），解析与校验在
 * {@link BehaviorAnalysisService}。
 */
@Component
@RequiredArgsConstructor
public class BehaviorAnalysisWorkflow {

    private static final String INSTRUCTION = """
            你是专业的用户行为分析师。下面会一次性给出该用户各维度的原始数据（JSON），请据此全面分析：
            1. 交易行为：加密货币现货、bStock 代币化美股、合约（分加密/大宗商品/TradFi 美股三个品类评述）、Prediction
            2. 游戏行为：Blackjack、Mines、Video Poker
            3. 风险画像：根据杠杆使用、破产历史、游戏频率判断风险等级(保守/稳健/激进/赌徒)
            4. 给出针对性建议

            某段数据形如 {"error": ...} 表示该维度这次没取到：对应数值填 0，并在建议里点明这块数据缺失，不要臆造。
            输出必须是严格符合下述 JSON Schema 的 JSON，不要输出任何其他文字：
            %s
            """.formatted(JsonSchemaGenerator.generateForType(BehaviorAnalysisReport.class));

    private final BehaviorDataCollector collector;

    /** 跑完整个 workflow，返回模型原文（可能带 ```json 围栏，调用方按老规矩 extractJson）。 */
    public String run(ChatModel chatModel, long userId, Consumer<String> onProgress) {
        List<Section> sections = collector.collect(userId, onProgress);

        ChatResponse response = chatService(chatModel)
                .execute(List.of(new UserMessage(buildPrompt(userId, sections))));

        Generation result = response.getResult();
        String text = result == null ? null : result.getOutput().getText();
        if (text == null || text.isBlank()) {
            throw new IllegalStateException("行为分析未返回有效内容");
        }
        return text;
    }

    /**
     * 走 {@link ResilientChatService} 而不是裸 {@code chatModel.call}：兜底切换与"读响应被掐"的补救挂在这一层。
     * 它的工厂签名要一个 ReactAgentBuilder，这里只借它捎系统提示——不挂工具、不 build 图，
     * 所以没有 ReAct 循环，落到底就是一次 {@code model.call}。
     */
    private ReactAgent.ChatService chatService(ChatModel chatModel) {
        return ResilientChatService.builder().model(chatModel).asFactory()
                .apply(ReactAgent.<MessagesState<Message>>builder().defaultSystem(INSTRUCTION));
    }

    private String buildPrompt(long userId, List<Section> sections) {
        StringBuilder prompt = new StringBuilder("分析用户#").append(userId)
                .append("的全部行为数据，以下是该用户各维度的原始数据：\n");
        for (Section section : sections) {
            prompt.append("\n## ").append(section.name()).append('（').append(section.detail()).append("）\n")
                    .append(section.json()).append('\n');
        }
        return prompt.toString();
    }
}
