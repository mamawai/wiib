package com.mawai.wiibcommon.constant;

import java.util.List;
import java.util.Set;

public final class QuantConstants {

    private QuantConstants() {}

    /**
     * 系统主动轮询的标的：Scheduler / ReflectionTask / Admin 全量触发都用这个。
     * DOGE 暂不参与主动量化，仅放开用户交易入口；待参数充分回测后再加入主动清单。
     */
    public static final List<String> WATCH_SYMBOLS = List.of("BTCUSDT", "ETHUSDT");

    /** 系统支持查询/操作的标的白名单（API 入参校验）。PAXG 已彻底清退（2026-07：残仓折现、数据清空）。 */
    public static final Set<String> ALLOWED_SYMBOLS = Set.of("BTCUSDT", "ETHUSDT", "DOGEUSDT");

    public static String normalizeSymbol(String symbol) {
        if (symbol == null || symbol.isBlank()) return "BTCUSDT";
        symbol = symbol.trim().toUpperCase();
        // 截后缀必须在 endsWith 判定之内算：放外面先算的话，"BTC" 这类短于 4 字符的简写
        // 会走到 substring(0, -1) 当场 StringIndexOutOfBoundsException——
        // 而本函数存在的意义正是接受简写，且它抛的是 IndexOutOfBounds，调用方 catch
        // IllegalArgumentException 也兜不住
        if (symbol.endsWith("USDT") || symbol.endsWith("USDC")) {
            symbol = symbol.substring(0, symbol.length() - 4);
        }
        if (symbol.isBlank()) return "BTCUSDT";
        String normalized = symbol + "USDT";
        if (!ALLOWED_SYMBOLS.contains(normalized)) {
            throw new IllegalArgumentException("仅支持 BTC、ETH、DOGE");
        }
        return normalized;
    }

    /**
     * 宽松归一（不校验白名单、永不抛错）：trim+大写，空/null 回退 BTCUSDT。
     * LLM 工具入口与事件监听用它——模型/事件漏传 symbol 时按 BTC 容错处理，不能把整条链打断。
     */
    public static String normalizeSymbolLenient(String symbol) {
        return symbol == null || symbol.isBlank() ? "BTCUSDT" : symbol.trim().toUpperCase();
    }
}
