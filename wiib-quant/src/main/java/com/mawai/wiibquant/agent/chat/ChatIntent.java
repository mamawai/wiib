package com.mawai.wiibquant.agent.chat;

/**
 * 功能按钮直发的一轮带的意图。
 * <p>
 * 与用户自己打字的普通一轮的区别只有两条：<b>不派专家</b>（要什么已经写死在意图里，
 * 让路由再猜一遍是白烧一次调用，还可能派出与本轮无关的专家），
 * 以及进汇总前多钉一句"本轮必须调这个工具"——按钮点下去就是明确的动作，不能落到模型的自由裁量。
 */
public enum ChatIntent {

    /** 行为分析按钮：直奔 analyze_my_behavior，见 {@link BehaviorToolkit} */
    BEHAVIOR("chat.intent.behavior");

    /** 进汇总前垫的指令在词表里的 key */
    private final String promptKey;

    ChatIntent(String promptKey) {
        this.promptKey = promptKey;
    }

    public String promptKey() {
        return promptKey;
    }
}
