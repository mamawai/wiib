package com.mawai.wiibservice.service;

import com.mawai.wiibcommon.dto.RankingDTO;
import com.mawai.wiibcommon.entity.CryptoPosition;
import com.mawai.wiibcommon.entity.Position;
import com.mawai.wiibcommon.entity.Settlement;
import com.mawai.wiibcommon.entity.User;
import com.mawai.wiibservice.mapper.CryptoOrderMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class RankingService {

    private final UserService userService;
    private final PositionService positionService;
    private final CryptoPositionService cryptoPositionService;
    private final CacheService cacheService;
    private final SettlementService settlementService;
    private final StockCacheService stockCacheService;
    private final CryptoOrderMapper cryptoOrderMapper;
    private final StringRedisTemplate stringRedisTemplate;

    private static final String RANKING_KEY = "ranking:top";
    private static final int TOP_N = 50;

    @Value("${trading.initial-balance:100000}")
    private BigDecimal initialBalance;

    public List<RankingDTO> getRanking() {
        List<RankingDTO> cached = cacheService.getList(RANKING_KEY);
        if (cached != null && !cached.isEmpty()) {
            return cached;
        }
        return refreshRanking();
    }

    public List<RankingDTO> refreshRanking() {
        log.info("开始刷新排行榜");
        long start = System.currentTimeMillis();

        List<User> users = userService.list();
        List<Position> allPositions = positionService.list();
        List<Settlement> pendingSettlements = settlementService.getAllPendingSettlements();

        // crypto持仓 + BTC实时价格
        List<CryptoPosition> allCryptoPositions = cryptoPositionService.list();
        Map<Long, List<CryptoPosition>> cryptoPositionMap = allCryptoPositions.stream()
                .collect(Collectors.groupingBy(CryptoPosition::getUserId));
        BigDecimal btcPrice = BigDecimal.ZERO;
        String btcPriceStr = stringRedisTemplate.opsForValue().get("market:price:BTCUSDT");
        if (btcPriceStr != null) btcPrice = new BigDecimal(btcPriceStr);

        // crypto待结算（SETTLING状态）
        Map<Long, BigDecimal> cryptoSettlingMap = cryptoOrderMapper.sumAllSettlingAmounts().stream()
                .collect(Collectors.toMap(
                        m -> ((Number) m.get("user_id")).longValue(),
                        m -> (BigDecimal) m.get("amount")
                ));

        // userId -> positions
        Map<Long, List<Position>> positionMap = allPositions.stream()
                .collect(Collectors.groupingBy(Position::getUserId));

        // userId -> pendingAmount
        Map<Long, BigDecimal> pendingMap = pendingSettlements.stream()
                .collect(Collectors.groupingBy(
                        Settlement::getUserId,
                        Collectors.reducing(BigDecimal.ZERO, Settlement::getAmount, BigDecimal::add)
                ));

        // 从Redis获取所有stockId
        Set<Long> stockIds = stockCacheService.getAllStockIds();
        List<Long> stockIdList = new ArrayList<>(stockIds);
        Map<Long, BigDecimal> cachedPrices = cacheService.getCurrentPrices(stockIdList);

        // stockId -> currentPrice（优先用缓存，否则从静态缓存取prevClose）
        Map<Long, BigDecimal> priceMap = stockIds.stream()
                .collect(Collectors.toMap(
                        id -> id,
                        id -> {
                            if (cachedPrices.containsKey(id)) {
                                return cachedPrices.get(id);
                            }
                            Map<String, String> stockStatic = stockCacheService.getStockStatic(id);
                            if (stockStatic != null && stockStatic.get("prevClose") != null) {
                                return new BigDecimal(stockStatic.get("prevClose"));
                            }
                            return BigDecimal.ZERO;
                        }
                ));

        List<RankingDTO> rankings = new ArrayList<>();
        for (User user : users) {
            BigDecimal balance = user.getBalance() != null ? user.getBalance() : BigDecimal.ZERO;
            BigDecimal frozen = user.getFrozenBalance() != null ? user.getFrozenBalance() : BigDecimal.ZERO;
            BigDecimal marginLoanPrincipal = user.getMarginLoanPrincipal() != null ? user.getMarginLoanPrincipal() : BigDecimal.ZERO;
            BigDecimal marginInterestAccrued = user.getMarginInterestAccrued() != null ? user.getMarginInterestAccrued() : BigDecimal.ZERO;

            BigDecimal marketValue = BigDecimal.ZERO;
            List<Position> positions = positionMap.get(user.getId());
            if (positions != null) {
                for (Position p : positions) {
                    BigDecimal price = priceMap.getOrDefault(p.getStockId(), BigDecimal.ZERO);
                    int qty = p.getTotalQuantity();
                    marketValue = marketValue.add(price.multiply(BigDecimal.valueOf(qty)));
                }
            }

            BigDecimal pendingSettlement = pendingMap.getOrDefault(user.getId(), BigDecimal.ZERO);
            pendingSettlement = pendingSettlement.add(cryptoSettlingMap.getOrDefault(user.getId(), BigDecimal.ZERO));

            // crypto持仓市值
            BigDecimal cryptoMarketValue = BigDecimal.ZERO;
            List<CryptoPosition> cryptoPositions = cryptoPositionMap.get(user.getId());
            if (cryptoPositions != null && btcPrice.compareTo(BigDecimal.ZERO) > 0) {
                for (CryptoPosition cp : cryptoPositions) {
                    cryptoMarketValue = cryptoMarketValue.add(btcPrice.multiply(cp.getTotalQuantity()));
                }
            }

            BigDecimal totalAssets = balance.add(frozen).add(marketValue).add(cryptoMarketValue).add(pendingSettlement)
                    .subtract(marginLoanPrincipal)
                    .subtract(marginInterestAccrued);
            RankingDTO dto = getRankingDTO(user, totalAssets);
            rankings.add(dto);
        }

        // 按总资产降序
        rankings.sort(Comparator.comparing(RankingDTO::getTotalAssets).reversed());

        // 取Top N并设置排名
        List<RankingDTO> topN = new ArrayList<>();
        for (int i = 0; i < Math.min(TOP_N, rankings.size()); i++) {
            RankingDTO dto = rankings.get(i);
            dto.setRank(i + 1);
            topN.add(dto);
        }

        // 缓存15分钟
        cacheService.setObject(RANKING_KEY, topN, 15, TimeUnit.MINUTES);

        long elapsed = System.currentTimeMillis() - start;
        log.info("排行榜刷新完成，共{}人，耗时{}ms", topN.size(), elapsed);
        return topN;
    }

    private RankingDTO getRankingDTO(User user, BigDecimal totalAssets) {
        BigDecimal profit = totalAssets.subtract(initialBalance);
        BigDecimal profitPct = initialBalance.compareTo(BigDecimal.ZERO) > 0
                ? profit.divide(initialBalance, 4, RoundingMode.HALF_UP).multiply(new BigDecimal("100"))
                : BigDecimal.ZERO;

        RankingDTO dto = new RankingDTO();
        dto.setUserId(user.getId());
        dto.setUsername(user.getUsername());
        dto.setAvatar(user.getAvatar());
        dto.setTotalAssets(totalAssets.setScale(2, RoundingMode.HALF_UP));
        dto.setProfitPct(profitPct.setScale(2, RoundingMode.HALF_UP));
        return dto;
    }
}
