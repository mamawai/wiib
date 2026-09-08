package com.mawai.wiibagent.llm;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 服务端搜索的过程事件，三个自研协议共用一个形状。
 * 模型层把它作为<b>空文本帧</b>发出，挂在 ChatResponseMetadata 的 {@link #KEY} 上（值是本记录的 JSON 串）；
 * ChatTurnRunner 逐帧取出交给 SSE 出口，前端据此画"正在搜索 / 搜索了 N 个网站"，来源攒到答案底部。
 *
 * @param phase   {@link #SEARCHING}=开始搜（query 可能还没有）；{@link #SEARCHED}=搜完（sources 是命中的站点）；
 *                {@link #CITED}=正文引用了某个来源（只并入本轮来源，不进过程轨）
 * @param query   搜索词，可空
 * @param sources 站点列表，可空表示没有
 */
public record SearchEvent(String phase, String query, List<Source> sources) {

    public static final String KEY = "wiib_search";
    public static final String SEARCHING = "searching";
    public static final String SEARCHED = "searched";
    public static final String CITED = "cited";

    public record Source(String url, String title) {

        /** 来源列表 ⇄ [{url,title}]：SSE 的 search/done 事件、历史接口、库里的 sources 列都是这个形状 */
        public static JSONArray toJson(List<Source> sources) {
            JSONArray list = new JSONArray();
            for (Source s : sources) {
                list.add(new JSONObject().fluentPut("url", s.url()).fluentPut("title", s.title()));
            }
            return list;
        }

        public static List<Source> fromJson(JSONArray list) {
            List<Source> sources = new ArrayList<>();
            if (list != null) {
                for (int i = 0; i < list.size(); i++) {
                    JSONObject s = list.getJSONObject(i);
                    sources.add(new Source(s.getString("url"), s.getString("title")));
                }
            }
            return sources;
        }
    }

    public static SearchEvent searching(String query) {
        return new SearchEvent(SEARCHING, query, List.of());
    }

    public static SearchEvent searched(String query, List<Source> sources) {
        return new SearchEvent(SEARCHED, query, sources);
    }

    public static SearchEvent cited(List<Source> sources) {
        return new SearchEvent(CITED, null, sources);
    }

    /** 与 SSE search 事件同形 */
    public JSONObject toJsonObject() {
        return new JSONObject().fluentPut("phase", phase).fluentPut("query", query)
                .fluentPut("sources", Source.toJson(sources));
    }

    public String toJson() {
        return toJsonObject().toJSONString();
    }

    public static SearchEvent parse(String json) {
        JSONObject o = JSON.parseObject(json);
        return new SearchEvent(o.getString("phase"), o.getString("query"), Source.fromJson(o.getJSONArray("sources")));
    }
}
