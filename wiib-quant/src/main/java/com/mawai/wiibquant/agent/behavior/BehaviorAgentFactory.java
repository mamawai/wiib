package com.mawai.wiibquant.agent.behavior;

import com.mawai.wiibquant.agent.SimInternalClient;
import com.mawai.wiibquant.agent.llm.ResilientChatService;
import org.bsc.langgraph4j.GraphStateException;
import org.bsc.langgraph4j.StateGraph;
import org.bsc.langgraph4j.prebuilt.MessagesState;
import org.bsc.langgraph4j.spring.ai.agent.ReactAgent;
import org.bsc.langgraph4j.spring.ai.serializer.jackson.SpringAIJacksonStateSerializer;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.util.json.schema.JsonSchemaGenerator;
import org.springframework.stereotype.Component;

import java.util.function.Consumer;

/**
 * 行为分析 agent：一个带工具的 ReAct 循环，跑完输出 {@link BehaviorAnalysisReport} 的 JSON。
 * <p>
 * 输出结构靠提示词里的 JSON Schema 约束——langgraph4j 的 ReactAgent 没有 outputType 那种
 * 结构化输出封装（spring-ai-alibaba 有，其内部也是把 schema 追加进提示词），这里显式做同一件事。
 */
@Component
public class BehaviorAgentFactory {

    private static final String INSTRUCTION = """
            你是专业的用户行为分析师。请根据工具获取的数据，全面分析用户在以下维度的行为模式：
            1. 交易行为：加密货币现货、bStock 代币化美股、合约（分加密/大宗商品/TradFi 美股三个品类评述）、Prediction
            2. 游戏行为：Blackjack、Mines、Video Poker
            3. 风险画像：根据杠杆使用、破产历史、游戏频率判断风险等级(保守/稳健/激进/赌徒)
            4. 给出针对性建议

            调用工具时传入用户ID获取各维度数据，然后综合分析。
            输出必须是严格符合下述 JSON Schema 的 JSON，不要输出任何其他文字：
            %s
            """.formatted(JsonSchemaGenerator.generateForType(BehaviorAnalysisReport.class));

    private final SimInternalClient simClient;

    public BehaviorAgentFactory(SimInternalClient simClient) {
        this.simClient = simClient;
    }

    /** 返回未编译的图：调用方自行 compile 并 invoke（行为分析是阻塞出报告，不需要流式）。 */
    public StateGraph<MessagesState<Message>> create(ChatModel chatModel, Consumer<String> onProgress) throws GraphStateException {
        BehaviorAnalysisTools tools = new BehaviorAnalysisTools(simClient, onProgress);

        return ReactAgent.<MessagesState<Message>>builder()
                .chatModel(chatModel)
                .stateSerializer(new SpringAIJacksonStateSerializer<>(MessagesState::new))
                .defaultSystem(INSTRUCTION)
                .toolsFromObject(tools)
                .build(ResilientChatService.builder().model(chatModel).asFactory());
    }
}
