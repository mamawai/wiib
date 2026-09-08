package com.mawai.wiibagent.llm;

import org.bsc.langgraph4j.prebuilt.MessagesState;
import org.bsc.langgraph4j.state.Channel;
import org.bsc.langgraph4j.state.Channels;

import java.util.ArrayList;
import java.util.Map;

/**
 * 全部 ReactAgent 图共用的 state schema：把框架默认的 {@code Channels.appender} 换成
 * {@code appenderWithDuplicate}，关掉一个静默丢消息的洞。
 * <p>
 * 默认那个是 {@code AppenderChannel.ReducerDisallowDuplicate}，判据为
 * {@code Objects.hash(旧) == Objects.hash(新)}——拿哈希跟<b>整段历史</b>比、不走 equals，
 * 命中就不 add 且不留日志。Spring AI 的 Message 全族是值语义 hashCode，于是同一句话问第二遍、
 * 专家两轮返回同样文本、固定垫话，都从第二次起消失（实测）。吞掉的是 AssistantMessage 时，
 * 下游症状是 {@code no AssistantMessage provided!}，错误信息与真因对不上。
 * <p>
 * 换掉是安全的：各链路每条消息都只 append 一次，去重挡不到任何真实的重复写入。
 */
public final class MessagesSchema {

    public static final Map<String, Channel<?>> SCHEMA =
            Map.of(MessagesState.MESSAGES_STATE, Channels.appenderWithDuplicate(ArrayList::new));

    private MessagesSchema() {
    }
}
