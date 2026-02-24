package com.mawai.wiibservice.fouronefour;

import lombok.Data;

import java.security.SecureRandom;
import java.util.*;
import java.util.stream.Collectors;

public class Card414Engine {

    // 牌面: H3=红心3, DT=方块10, SA=黑桃A, S2=黑桃2, JK=小王, BK=大王
    // 面值标识: 3,4,5,6,7,8,9,T,J,Q,K,A,2
    static final String[] RANKS = {"3", "4", "5", "6", "7", "8", "9", "T", "J", "Q", "K", "A", "2"};
    static final String[] SUITS = {"H", "D", "C", "S"};
    static final String[] HUN_PATH = {"3", "4", "5", "6", "7", "8", "9", "T", "J", "Q", "K", "A"};

    // 面值索引（基础排序，低到高）
    static final Map<String, Integer> RANK_INDEX = new HashMap<>();

    static {
        for (int i = 0; i < RANKS.length; i++) RANK_INDEX.put(RANKS[i], i);
    }

    // 牌型
    public enum PlayType {
        SINGLE, PAIR, DRAGON, DOUBLE_DRAGON, CANNON, BOMB, DOUBLE_JOKER, ROCKET
    }

    // 牌型优先级（用于跨类型比较）
    static int typePriority(PlayType t) {
        return switch (t) {
            case ROCKET -> 4;
            case DOUBLE_JOKER -> 3;
            case BOMB -> 2;
            case CANNON -> 1;
            default -> 0;
        };
    }

    @Data
    public static class PlayResult {
        private PlayType type;
        private int rank;     // 核心比较值（龙起始面值index / 其他面值index）
        private int length;   // 龙/双龙长度
        private List<String> cards;

        public PlayResult(PlayType type, int rank, int length, List<String> cards) {
            this.type = type;
            this.rank = rank;
            this.length = length;
            this.cards = cards;
        }
    }

    // ===== 一副牌 =====
    public static List<String> fullDeck() {
        List<String> deck = new ArrayList<>(54);
        for (String s : SUITS) {
            for (String r : RANKS) {
                deck.add(s + r);
            }
        }
        deck.add("JK");
        deck.add("BK");
        return deck;
    }

    public static void shuffle(List<String> deck) {
        Collections.shuffle(deck, new SecureRandom());
    }

    // 发牌: 14-14-13-13 从startSeat开始
    public static List<List<String>> deal(List<String> deck, int startSeat) {
        List<List<String>> hands = new ArrayList<>();
        for (int i = 0; i < 4; i++) hands.add(new ArrayList<>());

        int[] counts = {14, 14, 13, 13};
        int idx = 0;
        for (int i = 0; i < 4; i++) {
            int seat = (startSeat + i) % 4;
            for (int j = 0; j < counts[i]; j++) {
                hands.get(seat).add(deck.get(idx++));
            }
        }
        return hands;
    }

    // ===== 混排序 =====
    // 混牌提升至小王之下、2之上
    // 返回排序用的数值，值越大牌越大
    public static int effectiveRank(String card, String hunRank) {
        if ("BK".equals(card)) return 100;
        if ("JK".equals(card)) return 99;

        String rank = cardRank(card);
        int base = RANK_INDEX.getOrDefault(rank, 0);

        if (rank.equals(hunRank)) {
            return 98; // 混: 小王之下
        }
        return base; // 0-12, 其中2=12
    }

    // 面值排序（纯面值，不受混影响，用于龙/双龙）
    public static int faceRank(String card) {
        if ("BK".equals(card)) return 100;
        if ("JK".equals(card)) return 99;
        return RANK_INDEX.getOrDefault(cardRank(card), 0);
    }

    // 获取牌面值
    public static String cardRank(String card) {
        if ("JK".equals(card) || "BK".equals(card)) return card;
        return card.substring(1);
    }

    // 红心3检测
    public static boolean hasRedHeart3(List<String> hand) {
        return hand.contains("H3");
    }

    // 面值→比较值，JK/BK给正确的高值
    static int rankValue(String rank) {
        if ("BK".equals(rank)) return 100;
        if ("JK".equals(rank)) return 99;
        return RANK_INDEX.getOrDefault(rank, 0);
    }

    // ===== 牌型识别 =====
    public static PlayResult identify(List<String> cards) {
        int n = cards.size();
        if (n == 0) return null;

        List<String> ranks = cards.stream().map(Card414Engine::cardRank).collect(Collectors.toList());

        // 火箭: 两张4 + 一张A
        if (n == 3) {
            long count4 = ranks.stream().filter("4"::equals).count();
            long countA = ranks.stream().filter("A"::equals).count();
            if (count4 == 2 && countA == 1) {
                return new PlayResult(PlayType.ROCKET, 0, 0, cards);
            }
        }

        // 双王
        if (n == 2 && ranks.contains("JK") && ranks.contains("BK")) {
            return new PlayResult(PlayType.DOUBLE_JOKER, 0, 0, cards);
        }

        // 炸弹: 4张同面值
        if (n == 4) {
            String first = ranks.getFirst();
            if (ranks.stream().allMatch(first::equals)) {
                return new PlayResult(PlayType.BOMB, rankValue(first), 0, cards);
            }
        }

        // 炮: 3张同面值
        if (n == 3) {
            String first = ranks.getFirst();
            if (ranks.stream().allMatch(first::equals)) {
                return new PlayResult(PlayType.CANNON, rankValue(first), 0, cards);
            }
        }

        // 双龙: >=6张，偶数张，全是对子，连续>=3对，不含2和王
        if (n >= 6 && n % 2 == 0) {
            PlayResult dr = tryDoubleDragon(cards, ranks);
            if (dr != null) return dr;
        }

        // 龙: >=3张，全不同面值，连续，不含2和王
        if (n >= 3) {
            PlayResult dragon = tryDragon(cards, ranks);
            if (dragon != null) return dragon;
        }

        // 对子: 2张同面值
        if (n == 2) {
            if (ranks.get(0).equals(ranks.get(1))) {
                return new PlayResult(PlayType.PAIR, rankValue(ranks.getFirst()), 0, cards);
            }
        }

        // 单张
        if (n == 1) {
            return new PlayResult(PlayType.SINGLE, rankValue(ranks.getFirst()), 0, cards);
        }

        return null; // 不合法
    }

    private static PlayResult tryDragon(List<String> cards, List<String> ranks) {
        // 不含2和王
        for (String r : ranks) {
            if ("2".equals(r) || "JK".equals(r) || "BK".equals(r)) return null;
        }
        // 全不同面值
        Set<String> unique = new HashSet<>(ranks);
        if (unique.size() != ranks.size()) return null;

        // 面值index排序
        List<Integer> indices = ranks.stream()
                .map(r -> RANK_INDEX.getOrDefault(r, 0))
                .sorted()
                .toList();

        // 连续检查
        for (int i = 1; i < indices.size(); i++) {
            if (indices.get(i) != indices.get(i - 1) + 1) return null;
        }

        return new PlayResult(PlayType.DRAGON, indices.getFirst(), indices.size(), cards);
    }

    private static PlayResult tryDoubleDragon(List<String> cards, List<String> ranks) {
        // 不含2和王
        for (String r : ranks) {
            if ("2".equals(r) || "JK".equals(r) || "BK".equals(r)) return null;
        }

        // 统计每个面值出现次数
        Map<String, Long> freq = ranks.stream().collect(Collectors.groupingBy(r -> r, Collectors.counting()));
        // 每个面值必须恰好出现2次
        if (!freq.values().stream().allMatch(c -> c == 2)) return null;

        // 面值index排序
        List<Integer> indices = freq.keySet().stream()
                .map(r -> RANK_INDEX.getOrDefault(r, 0))
                .sorted()
                .toList();

        if (indices.size() < 3) return null;

        // 连续检查
        for (int i = 1; i < indices.size(); i++) {
            if (indices.get(i) != indices.get(i - 1) + 1) return null;
        }

        return new PlayResult(PlayType.DOUBLE_DRAGON, indices.getFirst(), indices.size(), cards);
    }

    // ===== 大小比较 =====
    // 当前出牌 current 能否压过上家出牌 last
    // hunRank: 当前混面值，影响单张/对子/炮/炸弹的比较
    public static boolean canBeat(PlayResult current, PlayResult last, String hunRank) {
        if (last == null) return true; // 自由出牌

        int curPri = typePriority(current.getType());
        int lastPri = typePriority(last.getType());

        // 高优先级类型压低优先级
        if (curPri > 0 && lastPri > 0) {
            if (curPri > lastPri) return true;
            if (curPri < lastPri) return false;
            // 同优先级: 炸弹比rank
            return compareRank(current.getRank(), last.getRank(), hunRank) > 0;
        }

        // 高优先级能压低优先级，但炮不能压双龙
        if (curPri > 0 && lastPri == 0) {
            return current.getType() != PlayType.CANNON || last.getType() != PlayType.DOUBLE_DRAGON;
        }
        if (curPri == 0 && lastPri > 0) return false;

        // 同类型比较
        if (current.getType() == last.getType()) {
            // 龙/双龙需要长度相同
            if (current.getType() == PlayType.DRAGON || current.getType() == PlayType.DOUBLE_DRAGON) {
                if (current.getLength() != last.getLength()) return false;
                // 龙/双龙按面值比较，不受混影响
                return current.getRank() > last.getRank();
            }
            // 单张/对子按混排序比较
            return compareRank(current.getRank(), last.getRank(), hunRank) > 0;
        }

        // 不同普通类型不能互压
        return false;
    }

    // 比较两个rank index，考虑混提升
    private static int compareRank(int r1, int r2, String hunRank) {
        int hunIdx = RANK_INDEX.getOrDefault(hunRank, -1);
        int eff1 = (r1 == hunIdx) ? 98 : r1;
        int eff2 = (r2 == hunIdx) ? 98 : r2;
        return Integer.compare(eff1, eff2);
    }

    // ===== 叉判断 =====
    // 有人出单张rank，检查某玩家手中是否有该rank的对子
    public static boolean canCha(String rank, List<String> hand) {
        long count = hand.stream().filter(c -> cardRank(c).equals(rank)).count();
        return count >= 2;
    }

    // 找出叉用的两张牌
    public static List<String> getChaCards(String rank, List<String> hand) {
        return hand.stream()
                .filter(c -> cardRank(c).equals(rank))
                .limit(2)
                .collect(Collectors.toList());
    }

    // ===== 勾判断 =====
    // 叉后，检查某玩家手中是否有该rank的单张（第4张）
    public static boolean canGou(String rank, List<String> hand) {
        return hand.stream().anyMatch(c -> cardRank(c).equals(rank));
    }

    // 找出勾用的牌
    public static String getGouCard(String rank, List<String> hand) {
        return hand.stream()
                .filter(c -> cardRank(c).equals(rank))
                .findFirst()
                .orElse(null);
    }

    // ===== 手牌排序 =====
    public static void sortHand(List<String> hand, String hunRank) {
        hand.sort((a, b) -> {
            int ra = effectiveRank(a, hunRank);
            int rb = effectiveRank(b, hunRank);
            if (ra != rb) return ra - rb;
            return a.compareTo(b);
        });
    }

    // ===== 混等级前进 =====
    public static String advanceHun(String current, int steps) {
        int idx = Arrays.asList(HUN_PATH).indexOf(current);
        if (idx == -1) return current;
        idx = Math.min(idx + steps, HUN_PATH.length - 1);
        return HUN_PATH[idx];
    }

    // 混等级index
    public static int hunIndex(String hun) {
        return Arrays.asList(HUN_PATH).indexOf(hun);
    }

    // 检查混是否到达A
    public static boolean isHunAtA(String hun) {
        return "A".equals(hun);
    }

    // 检查混是否>=J
    public static boolean isHunAtOrAboveJ(String hun) {
        return hunIndex(hun) >= hunIndex("J");
    }

    // 队友座位
    public static int partnerSeat(int seat) {
        return (seat + 2) % 4;
    }

    // 所属队伍
    public static String teamOf(int seat) {
        return (seat == 0 || seat == 2) ? "A" : "B";
    }
}
