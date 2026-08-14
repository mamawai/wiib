package com.mawai.wiibquant.agent.chat;

import com.mawai.wiibcommon.dto.WorkbenchMemoryEntry;
import com.mawai.wiibquant.mapper.WorkbenchMemoryMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatMemoryServiceTest {

    private final WorkbenchMemoryMapper memoryMapper = mock(WorkbenchMemoryMapper.class);
    private final ChatMemoryService service = new ChatMemoryService(memoryMapper);

    @Test
    void rememberExtractsMentionedSymbol() {
        service.remember(1L, "BTC 现在脆弱度怎么样", "脆弱度61，偏高");

        // 计数自增交给 SQL upsert（原子，无先读后写竞态），这里只验证提取到了正确的 symbol
        verify(memoryMapper).upsert(eq(1L), eq("BTCUSDT"), eq("BTC 现在脆弱度怎么样"), eq("脆弱度61，偏高"));
    }

    @Test
    void rememberMatchesSymbolCaseInsensitively() {
        service.remember(1L, "btc 波动预测", "H6 预计 120bps");

        verify(memoryMapper).upsert(eq(1L), eq("BTCUSDT"), anyString(), anyString());
    }

    @Test
    void rememberSkipsWhenNoWatchSymbolMentioned() {
        service.remember(1L, "今天天气如何", "不知道");

        verify(memoryMapper, never()).upsert(anyLong(), anyString(), anyString(), anyString());
    }

    /** 裸 contains 的子串假阳性：whether 含 ETH——英文单词里嵌着币名不算提及 */
    @Test
    void rememberIgnoresCoinEmbeddedInEnglishWord() {
        service.remember(1L, "I wonder whether the market will recover", "maybe");

        verify(memoryMapper, never()).upsert(anyLong(), anyString(), anyString(), anyString());
    }

    /** 周期单位贴着币名是高频输入形态（"未来1hbtc会涨吗"），单位的尾字母不能挡掉提及 */
    @Test
    void rememberMatchesCoinAfterTimeframeUnit() {
        service.remember(1L, "未来1hbtc会涨吗", "偏多");

        verify(memoryMapper).upsert(eq(1L), eq("BTCUSDT"), anyString(), anyString());
    }

    /** 中文提问里币名两侧贴汉字是常态（"看看BTC行情"），词边界只认英文字母，这种必须认出来 */
    @Test
    void rememberMatchesCoinAdjacentToChinese() {
        service.remember(1L, "看看BTC行情", "还行");

        verify(memoryMapper).upsert(eq(1L), eq("BTCUSDT"), anyString(), anyString());
    }

    @Test
    void rememberDegradesOnStoreFailure() {
        when(memoryMapper.upsert(anyLong(), anyString(), anyString(), anyString()))
                .thenThrow(new RuntimeException("db down"));

        service.remember(1L, "BTC 现在怎么样", "还行"); // 记忆是增益不是主链，不该往上抛

        verify(memoryMapper).upsert(anyLong(), anyString(), anyString(), anyString());
    }

    @Test
    void recallBuildsMemoryPrefix() {
        when(memoryMapper.selectRecent(anyLong(), anyInt()))
                .thenReturn(List.of(entry("BTCUSDT", 5L, "脆弱度怎么样")));

        String memory = service.recall(1L);

        assertThat(memory).contains("BTCUSDT").contains("5次").contains("脆弱度怎么样");
    }

    @Test
    void recallReturnsEmptyWhenNoMemory() {
        when(memoryMapper.selectRecent(anyLong(), anyInt())).thenReturn(List.of());

        assertThat(service.recall(1L)).isEmpty();
    }

    @Test
    void recallDegradesToEmptyOnFailure() {
        when(memoryMapper.selectRecent(anyLong(), anyInt())).thenThrow(new RuntimeException("db down"));

        assertThat(service.recall(1L)).isEmpty();
    }

    private static WorkbenchMemoryEntry entry(String symbol, long hitCount, String lastQuestion) {
        WorkbenchMemoryEntry e = new WorkbenchMemoryEntry();
        e.setSymbol(symbol);
        e.setHitCount(hitCount);
        e.setLastQuestion(lastQuestion);
        return e;
    }
}
