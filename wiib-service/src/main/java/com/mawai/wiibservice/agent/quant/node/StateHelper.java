package com.mawai.wiibservice.agent.quant.node;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.fastjson2.JSON;

final class StateHelper {

    private StateHelper() {}

    static String stateJson(OverAllState state, String key) {
        return state.value(key).map(v -> {
            if (v instanceof String s) return s;
            return JSON.toJSONString(v);
        }).orElse("无");
    }

    static int stateInt(OverAllState state, String key) {
        return ((Number) state.value(key).orElse(0)).intValue();
    }
}
