package com.mawai.wiibcommon.util;

import com.mawai.wiibcommon.enums.BuffType;
import com.mawai.wiibcommon.enums.Rarity;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

public final class BuffDrawUtil {

    private static final int TOTAL_WEIGHT;
    private static final int[] RARITY_THRESHOLDS;
    private static final Rarity[] RARITIES = Rarity.values();
    private static final Map<Rarity, List<BuffType>> BUFF_BY_RARITY;

    static {
        int sum = 0;
        RARITY_THRESHOLDS = new int[RARITIES.length];
        for (int i = 0; i < RARITIES.length; i++) {
            sum += RARITIES[i].getWeight();
            RARITY_THRESHOLDS[i] = sum;
        }
        TOTAL_WEIGHT = sum;

        BUFF_BY_RARITY = new EnumMap<>(Rarity.class);
        for (Rarity r : RARITIES) {
            BUFF_BY_RARITY.put(r, List.of());
        }
        Map<Rarity, java.util.ArrayList<BuffType>> temp = new EnumMap<>(Rarity.class);
        for (Rarity r : RARITIES) {
            temp.put(r, new java.util.ArrayList<>());
        }
        for (BuffType b : BuffType.values()) {
            temp.get(b.getRarity()).add(b);
        }
        for (Rarity r : RARITIES) {
            BUFF_BY_RARITY.put(r, List.copyOf(temp.get(r)));
        }
    }

    private BuffDrawUtil() {}

    public static Rarity drawRarity() {
        int rand = ThreadLocalRandom.current().nextInt(TOTAL_WEIGHT);
        for (int i = 0; i < RARITY_THRESHOLDS.length; i++) {
            if (rand < RARITY_THRESHOLDS[i]) {
                return RARITIES[i];
            }
        }
        return RARITIES[RARITIES.length - 1];
    }

    public static BuffType drawBuff() {
        Rarity rarity = drawRarity();
        return drawBuffByRarity(rarity);
    }

    public static BuffType drawBuffByRarity(Rarity rarity) {
        List<BuffType> candidates = BUFF_BY_RARITY.get(rarity);
        return candidates.get(ThreadLocalRandom.current().nextInt(candidates.size()));
    }
}
