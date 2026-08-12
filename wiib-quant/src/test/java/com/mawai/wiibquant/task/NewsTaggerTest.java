package com.mawai.wiibquant.task;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 打标输出解析。词表是封闭集：宁可漏标不许瞎标——
 * 词表外的产出必须静默丢弃，图标挂错 K 线比不挂更误导。
 */
class NewsTaggerTest {

    private static final List<String> VOCAB = List.of("OIL", "GOLD", "BTC", "COIN", "MSTR", "TSLA", "NVDA");

    @Test
    void 正常解析多条多标() {
        Map<Long, String> tags = NewsTagger.parse("""
                [{"id":1,"tags":["BTC","GOLD"]},{"id":2,"tags":[]},{"id":3,"tags":["OIL"]}]""", VOCAB);

        assertThat(tags).containsEntry(1L, "BTC,GOLD")
                .containsEntry(2L, "")
                .containsEntry(3L, "OIL");
    }

    @Test
    void 词表外标签静默丢弃() {
        // 模型自作主张给了 ETH 和 SPX：不在词表就不存在，剩下的合法标保留
        Map<Long, String> tags = NewsTagger.parse(
                "[{\"id\":1,\"tags\":[\"ETH\",\"BTC\",\"SPX\"]}]", VOCAB);

        assertThat(tags).containsEntry(1L, "BTC");
    }

    @Test
    void 小写与空白归一后再对词表() {
        Map<Long, String> tags = NewsTagger.parse(
                "[{\"id\":1,\"tags\":[\" btc \",\"gold\"]}]", VOCAB);

        assertThat(tags).containsEntry(1L, "BTC,GOLD");
    }

    @Test
    void JSON外裹着废话也解析得出() {
        // 轻模型常见毛病：明令只输出 JSON 还是要加一句"好的，以下是分类结果"
        Map<Long, String> tags = NewsTagger.parse("""
                好的，以下是分类结果：
                [{"id":7,"tags":["TSLA"]}]
                以上。""", VOCAB);

        assertThat(tags).containsEntry(7L, "TSLA");
    }

    @Test
    void 无JSON数组时抛出让采集方跳过本轮() {
        // 抛而不是静默空标：空标会被落库，这批快讯从此永远失去打标机会
        assertThatThrownBy(() -> NewsTagger.parse("我不知道怎么分类", VOCAB))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 缺id或重复标签的条目不炸() {
        Map<Long, String> tags = NewsTagger.parse(
                "[{\"tags\":[\"BTC\"]},{\"id\":2,\"tags\":[\"BTC\",\"BTC\"]}]", VOCAB);

        assertThat(tags).hasSize(1).containsEntry(2L, "BTC");
    }
}
