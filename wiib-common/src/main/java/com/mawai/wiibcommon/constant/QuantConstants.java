package com.mawai.wiibcommon.constant;

import java.util.List;
import java.util.Set;

public final class QuantConstants {

    private QuantConstants() {}

    public static final List<String> WATCH_SYMBOLS = List.of("BTCUSDT", "ETHUSDT", "PAXGUSDT");

    public static final Set<String> ALLOWED_SYMBOLS = Set.copyOf(WATCH_SYMBOLS);
}
