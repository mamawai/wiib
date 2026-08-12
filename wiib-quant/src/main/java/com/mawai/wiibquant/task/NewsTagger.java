package com.mawai.wiibquant.task;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.mawai.wiibquant.agent.quant.domain.news.NewsFlash;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 快讯批量打标：一批快讯一次轻模型调用，产出 快讯id → 逗号标签串。
 * <p>
 * 词表是封闭集（配置给定），模型只许从里面选；选不出/不确定就空着——
 * 宁可漏标不许瞎标：图标挂错 K 线比不挂更误导。词表外的产出一律丢弃。
 * <p>
 * 整批调用失败往上抛，由采集方跳过本轮（快讯还在拉取窗口里，下轮重试自愈）——
 * 失败时硬插空标会让这批永远失去打标机会（去重键挡住重入）。
 */
@Slf4j
@Component
public class NewsTagger {

    /** 单条正文进提示词的长度上限：快讯本就短，超长的是行情简报类，头部信息足够判定标的 */
    private static final int CONTENT_CLIP = 400;

    private static final String PROMPT = """
            你是金融新闻分类器。对下面每条快讯判断它与哪些标的明确相关，只许从这个词表里选（可多选）：
            %s
            规则：
            - BTC 代表比特币或加密货币大盘整体方向的消息；只涉某个山寨币自身的消息不算
            - OIL=原油，GOLD=黄金；美股代码只在消息明确涉及该公司时才选
            - 判断不出或与词表标的都无关，tags 给空数组，不要猜
            只输出 JSON 数组，不要任何其他文字，格式：
            [{"id":快讯id,"tags":["BTC"]},{"id":快讯id,"tags":[]}]

            快讯列表：
            %s""";

    /**
     * @return 快讯id → 逗号标签串（可为空串）。缺席的条目按空标处理。
     * @throws RuntimeException 模型调用或整体解析失败——调用方跳过本轮，下轮重试
     */
    public Map<Long, String> tag(ChatModel model, List<NewsFlash> flashes, List<String> vocabulary) {
        StringBuilder list = new StringBuilder();
        for (NewsFlash f : flashes) {
            String content = f.plainContent();
            if (content.length() > CONTENT_CLIP) {
                content = content.substring(0, CONTENT_CLIP);
            }
            list.append("id=").append(f.id()).append(" 标题：").append(f.title())
                    .append(" 正文：").append(content).append('\n');
        }
        String output = model.call(new Prompt(
                        PROMPT.formatted(String.join("、", vocabulary), list)))
                .getResult().getOutput().getText();
        return parse(output, vocabulary);
    }

    /** 宽进严出：JSON 前后可能裹着废话，截取首尾中括号；词表外标签静默丢弃。 */
    static Map<Long, String> parse(String output, List<String> vocabulary) {
        int from = output.indexOf('[');
        int to = output.lastIndexOf(']');
        if (from < 0 || to <= from) {
            throw new IllegalStateException("打标输出不含 JSON 数组: "
                    + output.substring(0, Math.min(200, output.length())));
        }
        JSONArray arr = JSON.parseArray(output.substring(from, to + 1));
        Map<Long, String> result = new HashMap<>();
        for (int i = 0; i < arr.size(); i++) {
            JSONObject item = arr.getJSONObject(i);
            if (item == null || !item.containsKey("id")) {
                continue;
            }
            JSONArray tags = item.getJSONArray("tags");
            // LinkedHashSet：去重且保模型给的顺序
            Set<String> valid = new LinkedHashSet<>();
            if (tags != null) {
                for (Object tag : tags) {
                    if (tag instanceof String s && vocabulary.contains(s.trim().toUpperCase())) {
                        valid.add(s.trim().toUpperCase());
                    }
                }
            }
            result.put(item.getLongValue("id"), String.join(",", valid));
        }
        return result;
    }
}
