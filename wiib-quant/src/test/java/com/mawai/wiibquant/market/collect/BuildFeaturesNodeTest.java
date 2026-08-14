package com.mawai.wiibquant.market.collect;

import org.bsc.langgraph4j.state.AgentState;
import com.mawai.wiibcommon.enums.KlineInterval;
import com.mawai.wiibquant.market.domain.FeatureSnapshot;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class BuildFeaturesNodeTest {

    @Test
    void nodeWrapperKeepsApplyOutputShapeForEmptyInput() {
        BuildFeaturesNode node = new BuildFeaturesNode(null, KlineInterval.M1);

        Map<String, Object> result = node.apply(new AgentState(Map.of("target_symbol", "BTCUSDT")));

        assertThat(result).containsKeys("feature_snapshot", "indicator_map", "price_change_map");
        FeatureSnapshot snapshot = (FeatureSnapshot) result.get("feature_snapshot");
        assertThat(snapshot.symbol()).isEqualTo("BTCUSDT");
        assertThat(snapshot.qualityFlags())
                .contains("NO_INDICATORS", "NO_PRICE", "PARTIAL_KLINE_DATA");
    }
}
