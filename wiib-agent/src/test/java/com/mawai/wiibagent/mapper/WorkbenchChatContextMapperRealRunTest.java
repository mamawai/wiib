package com.mawai.wiibagent.mapper;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 真跑验收（非单测）：真连本地 PG 走一遍 BYTEA 的存取往返，不烧 LLM。
 * <p>
 * 补的是 mock 遮住的那一层——{@link com.mawai.wiibagent.chat.ChatContextStore} 的单测把
 * mapper 整个 mock 掉了，MyBatis 的 TypeHandler 选型根本没参与，于是
 * "selectState 返回 byte[] 被当成多行、拿 ByteTypeHandler 读 BYTEA"这种坑单测全绿也照样上线，
 * 线上表现成每轮对话都读不到历史（续聊失忆）。这条测试就是那层的守门人。
 * <p>
 * 跑法（项目根）：
 * <pre>
 * WIIB_REAL_RUN=1 mvn -o test -pl wiib-agent -am -DskipTests=false \
 *   -Dtest=WorkbenchChatContextMapperRealRunTest -Dsurefire.failIfNoSpecifiedTests=false
 * </pre>
 */
@SpringBootTest(properties = {
        // 只验一张表的存取：策略信号/实盘执行/AI 分析轨全关，别让背景任务在测试期间下单写库
        "strategy.runtime.enabled=false",
        "strategy.execution.enabled=false",
        "agent.analysis.enabled=false"
})
@EnabledIfEnvironmentVariable(named = "WIIB_REAL_RUN", matches = "1")
class WorkbenchChatContextMapperRealRunTest {

    @Autowired
    private WorkbenchChatContextMapper mapper;

    /**
     * 首字节刻意用 0xAC 0xED 0x00 0x05：生产落的就是这个开头（序列化魔数），
     * 且带高位字节和 0x00——BYTEA 走错 TypeHandler 时最先炸在这类字节上。
     */
    private static byte[] sampleState() {
        byte[] head = {(byte) 0xAC, (byte) 0xED, 0x00, 0x05};
        byte[] body = "{\"messages\":[{\"@type\":\"USER\",\"text\":\"BTC 现在怎么样\"}]}"
                .getBytes(StandardCharsets.UTF_8);
        byte[] all = new byte[head.length + body.length];
        System.arraycopy(head, 0, all, 0, head.length);
        System.arraycopy(body, 0, all, head.length, body.length);
        return all;
    }

    @Test
    void 存取往返字节无损且整体替换与删除到位() {
        // 唯一 session：真库里跑，绝不碰用户的真会话行
        String session = "wb-test-" + UUID.randomUUID();
        try {
            assertThat(mapper.selectState(session)).as("新会话应无行").isEmpty();

            byte[] state = sampleState();
            mapper.upsert(session, 1L, state);

            List<byte[]> rows = mapper.selectState(session);
            assertThat(rows).hasSize(1);
            assertThat(rows.getFirst()).as("BYTEA 往返必须一个字节不差").isEqualTo(state);

            // 一会话一行整体替换：再写一份，仍是一行且内容换成新的
            byte[] second = "{\"messages\":[]}".getBytes(StandardCharsets.UTF_8);
            mapper.upsert(session, 1L, second);
            List<byte[]> after = mapper.selectState(session);
            assertThat(after).hasSize(1);
            assertThat(after.getFirst()).isEqualTo(second);

            assertThat(mapper.deleteBySessionId(session)).isEqualTo(1);
            assertThat(mapper.selectState(session)).as("删后应无行").isEmpty();
        } finally {
            mapper.deleteBySessionId(session);   // 中途断言失败也不留脏行
        }
    }
}
