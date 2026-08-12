package com.mawai.wiibquant.agent.chat;

import com.mawai.wiibcommon.entity.UserLlmConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 真跑验收（非单测）：起完整 Spring 上下文，真连 DB 配置的 LLM 代理与本地 PG/Redis，
 * 按 Controller 同款方式驱动一轮完整对话。单测把上游 mock 掉了（mock ChatModel），
 * 绿了不代表链路通——框架契约边界的验证空白由本类补。
 * <p>
 * 会真烧 LLM token，默认跳过，显式开启才跑。跑法（项目根）：
 * <pre>
 * WIIB_REAL_RUN=1 mvn -o test -pl wiib-quant -am -DskipTests=false \
 *   -Dtest=ChatWorkbenchRealRunTest -Dsurefire.failIfNoSpecifiedTests=false
 * </pre>
 * 日志看点：[Responses] 请求 tool_choice=… / toolCalls=…、[NewsTool] 预取、[Workbench] 派发、[TurnMetrics]。
 */
@SpringBootTest(properties = {
        // 只验对话链路：策略信号/实盘执行/AI 分析轨全关，测试期间不许背景任务下单写库
        "strategy.runtime.enabled=false",
        "strategy.execution.enabled=false",
        "quant.analysis.enabled=false"
})
@EnabledIfEnvironmentVariable(named = "WIIB_REAL_RUN", matches = "1")
class ChatWorkbenchRealRunTest {

    private static final Logger log = LoggerFactory.getLogger(ChatWorkbenchRealRunTest.class);

    /** 挑 1 号不再是因为门只对管理员开（已对全体登录用户开放），纯粹因为真跑要烧的那份 BYOK 配在它名下 */
    private static final long ADMIN_USER_ID = 1L;

    @Autowired
    private ChatAgentFactory chatAgentFactory;

    @Autowired
    private ChatTurnRunner chatTurnRunner;

    /** 真跑就得烧真配置：这一跑的全部价值就在于走用户自己那份 BYOK，绝不在这里造一份假的 */
    @Autowired
    private UserLlmConfigService userLlmConfigService;

    @Test
    void 一轮新闻加行情提问全链路真跑() {
        UserLlmConfig llmConfig = userLlmConfigService.get(ADMIN_USER_ID);
        assertThat(llmConfig).as("先用管理员账号在 /api/ai/llm-config 配一份 BYOK 端点再跑").isNotNull();

        ChatAgentFactory.Leaves leaves = chatAgentFactory.leavesFor(llmConfig);
        String sessionId = "wb-1-realrun-" + UUID.randomUUID();
        List<ChatTurnRunner.ExpertProgress> events = new CopyOnWriteArrayList<>();
        StringBuilder answer = new StringBuilder();

        // 纯新闻问题复刻实测暴露过的病：summarizer 拿到专家清单后用自己的搜索重写一遍
        chatTurnRunner.run(leaves, ADMIN_USER_ID, sessionId,
                "最近有什么重要的加密货币新闻？", answer::append, events::add);

        for (ChatTurnRunner.ExpertProgress e : events) {
            log.info("[RealRun] 专家事件 agent={} phase={} text={}", e.agent(), e.phase(),
                    e.text() == null ? null : e.text().substring(0, Math.min(2000, e.text().length())));
        }
        log.info("[RealRun] 最终回答（{}字）：{}", answer.length(), answer);

        // 链路底线：汇总有产出；新闻问题至少派出过一个专家
        assertThat(answer.toString()).isNotBlank();
        Map<String, Long> starts = events.stream()
                .filter(e -> ChatTurnRunner.ExpertProgress.START.equals(e.phase()))
                .collect(Collectors.groupingBy(ChatTurnRunner.ExpertProgress::agent, Collectors.counting()));
        assertThat(starts).isNotEmpty();
        // 同一专家最多 start 一次：去重生效，循环必然收敛
        assertThat(starts).allSatisfy((agent, count) -> assertThat(count).isLessThanOrEqualTo(1L));
        // 输出契约：news_agent 的 BlockBeats 条目必须存活在最终回答里（标可因联网佐证升级为
        // 合并标）——只剩补充源的标即 summarizer 丢弃专家清单自己重写了，正是要防的回归
        assertThat(answer.toString()).containsAnyOf("[BlockBeats]", chatAgentFactory.mergedTag());
    }
}
