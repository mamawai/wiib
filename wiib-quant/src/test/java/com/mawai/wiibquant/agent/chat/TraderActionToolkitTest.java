package com.mawai.wiibquant.agent.chat;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.annotation.Tool;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

/**
 * 挂在 summarizer 上的三个动作工具。这一层只做一件事：<b>把待填的表单卡推出去</b>，
 * 再按推没推出去分叉话术——工具本身没有执行能力，动作语义（钳制/准入/互斥）
 * 全在 {@code TraderActionServiceTest}，从这里够不着。
 */
class TraderActionToolkitTest {

    private static final String SESSION = "wb-42-x";

    /** 推出去的每一张卡的 data（form + prefill） */
    private final List<JSONObject> pushed = new ArrayList<>();
    private final WorkbenchRunRegistry runRegistry = spy(new WorkbenchRunRegistry());
    private final TraderActionToolkit toolkit = new TraderActionToolkit(runRegistry, 42L);

    /** 常态：这一轮有 SSE 通道，会话号已由闸门设进 ToolRunContext */
    @BeforeEach
    void openChannel() {
        runRegistry.start(SESSION, (event, data) -> pushed.add(data));
        ToolRunContext.set(SESSION);
    }

    @AfterEach
    void closeChannel() {
        ToolRunContext.clear();
        runRegistry.finish(SESSION);
    }

    private static JSONObject parse(String json) {
        return JSON.parseObject(json);
    }

    /** formType 是前端认卡的唯一依据，三个工具各推各的，串了就是点唤醒弹出复盘 */
    @Test
    void 三个工具各推各的表单() {
        toolkit.wakeTrader();
        toolkit.reviewTraderNow();
        toolkit.leaveNoteToTrader("仓位轻点", 3);

        assertThat(pushed).extracting(d -> d.getString("form"))
                .containsExactly("wake", "review", "note");
    }

    /** 草稿与轮次都得随卡带过去：用户要在卡上看着改，而不是重打一遍 */
    @Test
    void 留言把草稿与轮次一起预填() {
        String out = toolkit.leaveNoteToTrader("今晚有 CPI，仓位放轻", 3);

        JSONObject prefill = pushed.getFirst().getJSONObject("prefill");
        assertThat(prefill.getString("note")).isEqualTo("今晚有 CPI，仓位放轻");
        assertThat(prefill.getIntValue("rounds")).isEqualTo(3);
        assertThat(parse(out).getBooleanValue("ok")).isTrue();
    }

    /** 模型没说几轮就别替它填：塞个空值进去，卡上会显示成"0 轮"这种谁也没要过的数 */
    @Test
    void 不填轮次时预填里没有轮次字段() {
        toolkit.leaveNoteToTrader("仓位轻点", null);

        JSONObject prefill = pushed.getFirst().getJSONObject("prefill");
        assertThat(prefill.getString("note")).isEqualTo("仓位轻点");
        assertThat(prefill.containsKey("rounds")).isFalse();
    }

    /**
     * 会话已结束/断连的那一轮，卡根本推不出去。这时候答"表单已打开"是一句用户
     * 永远兑现不了的话，而它还会落进对话历史——必须改口让他自己去面板点。
     */
    @Test
    void 推不出去时如实说没打开() {
        runRegistry.finish(SESSION);

        JSONObject out = parse(toolkit.wakeTrader());

        assertThat(out.getBooleanValue("ok")).isFalse();
        assertThat(out.getString("message")).contains("没能打开").contains("面板");
        assertThat(pushed).isEmpty();
    }

    /** 补答轮更阴：会话还挂着"运行中"，但出口是 NO_EMITTER 占位，一样推不出去 */
    @Test
    void 补答轮同样如实说没打开() {
        runRegistry.start(SESSION, WorkbenchRunRegistry.NO_EMITTER);

        JSONObject out = parse(toolkit.reviewTraderNow());

        assertThat(out.getBooleanValue("ok")).isFalse();
        assertThat(out.getString("message")).contains("没能打开");
        assertThat(pushed).isEmpty();
    }

    /** 没有会话号（不在工具执行栈里）时连推都不该推，更不能回一句"已打开" */
    @Test
    void 没有会话号时不推表单() {
        ToolRunContext.clear();

        JSONObject out = parse(toolkit.leaveNoteToTrader("仓位轻点", 1));

        assertThat(out.getBooleanValue("ok")).isFalse();
        verify(runRegistry, never()).publishForm(any(), any(), any());
    }

    /**
     * 工具签名里绝不能出现用户/trader 标识类参数——有的话模型就能替别人开表单。
     * 这条比"传对了 userId"更根本：那条验实现，这条堵的是接口层面根本给不了。
     */
    @Test
    void 工具不暴露任何身份参数() {
        for (Method m : TraderActionToolkit.class.getDeclaredMethods()) {
            if (m.getAnnotation(Tool.class) == null) {
                continue;
            }
            assertThat(m.getParameterTypes())
                    .as("工具 %s 的参数", m.getName())
                    .allSatisfy(type -> assertThat(type).isIn(String.class, Integer.class));  // 只有 note / rounds
        }
    }

}
