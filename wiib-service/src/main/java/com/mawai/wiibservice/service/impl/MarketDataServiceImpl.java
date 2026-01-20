package com.mawai.wiibservice.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.mawai.wiibcommon.entity.Company;
import com.mawai.wiibcommon.entity.PriceTick;
import com.mawai.wiibcommon.entity.Stock;
import com.mawai.wiibservice.mapper.PriceTickMapper;
import com.mawai.wiibservice.service.AiService;
import com.mawai.wiibservice.service.CompanyService;
import com.mawai.wiibservice.service.MarketDataService;
import com.mawai.wiibservice.service.StockService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class MarketDataServiceImpl implements MarketDataService {

    private final StockService stockService;
    private final CompanyService companyService;
    private final PriceTickMapper priceTickMapper;
    private final StringRedisTemplate redisTemplate;
    private final AiService aiService;

    @Override
    @Transactional
    public void generateNextDayMarketData(LocalDate targetDate) {
        log.info("开始生成次日行情: {}", targetDate);

        List<Stock> stocks = stockService.list();

        for (Stock stock : stocks) {
            List<PriceTick> ticks = generateStockTicks(stock, targetDate);
            ticks.forEach(priceTickMapper::insert);
            log.info("生成股票{}走势完成，共{}个点", stock.getCode(), ticks.size());
        }

        log.info("次日行情生成完成");
    }

    @Override
    public void loadDayDataToRedis(LocalDate date) {
        log.info("加载{}行情到Redis", date);

        List<Stock> stocks = stockService.list();
        LocalDateTime startTime = date.atTime(9, 30);
        LocalDateTime endTime = date.atTime(15, 0);

        for (Stock stock : stocks) {
            List<PriceTick> ticks = priceTickMapper.selectList(
                new LambdaQueryWrapper<PriceTick>()
                    .eq(PriceTick::getStockId, stock.getId())
                    .between(PriceTick::getTickTime, startTime, endTime)
                    .orderByAsc(PriceTick::getTickTime)
            );

            if (ticks.isEmpty()) continue;

            // 分时数据
            String tickKey = String.format("tick:%s:%d", date, stock.getId());
            for (PriceTick tick : ticks) {
                String field = tick.getTickTime().toLocalTime().toString();
                String value = tick.getPrice() + "," + tick.getVolume();
                redisTemplate.opsForHash().put(tickKey, field, value);
            }

            // 当日汇总：open, high, low, last, prevClose
            BigDecimal open = ticks.get(0).getPrice();
            BigDecimal high = ticks.stream().map(PriceTick::getPrice).max(BigDecimal::compareTo).orElse(open);
            BigDecimal low = ticks.stream().map(PriceTick::getPrice).min(BigDecimal::compareTo).orElse(open);
            BigDecimal last = ticks.get(ticks.size() - 1).getPrice();

            String dailyKey = String.format("stock:daily:%s:%d", date, stock.getId());
            redisTemplate.opsForHash().put(dailyKey, "open", open.toString());
            redisTemplate.opsForHash().put(dailyKey, "high", high.toString());
            redisTemplate.opsForHash().put(dailyKey, "low", low.toString());
            redisTemplate.opsForHash().put(dailyKey, "last", last.toString());
            redisTemplate.opsForHash().put(dailyKey, "prevClose", stock.getPrevClose().toString());

            log.info("加载股票{}行情到Redis，共{}个点", stock.getCode(), ticks.size());
        }
    }

    @Override
    public Map<String, Object> getTickByTime(Long stockId, LocalDate date, LocalTime time) {
        String key = String.format("tick:%s:%d", date, stockId);
        String value = (String) redisTemplate.opsForHash().get(key, time.toString());

        if (value == null) return null;

        String[] parts = value.split(",");
        Map<String, Object> result = new HashMap<>();
        result.put("price", new BigDecimal(parts[0]));
        result.put("volume", Long.parseLong(parts[1]));
        return result;
    }

    @Override
    public List<Map<String, Object>> getDayTicks(Long stockId, LocalDate date) {
        String key = String.format("tick:%s:%d", date, stockId);
        Map<Object, Object> entries = redisTemplate.opsForHash().entries(key);

        List<Map<String, Object>> result = new ArrayList<>();
        entries.forEach((time, value) -> {
            String[] parts = value.toString().split(",");
            Map<String, Object> tick = new HashMap<>();
            tick.put("time", time);
            tick.put("price", new BigDecimal(parts[0]));
            tick.put("volume", Long.parseLong(parts[1]));
            result.add(tick);
        });

        result.sort(Comparator.comparing(m -> m.get("time").toString()));
        return result;
    }

    @Override
    public List<Map<String, Object>> getHistoryClose(Long stockId, int days) {
        LocalDate endDate = LocalDate.now().minusDays(1);
        LocalDate startDate = endDate.minusDays(days);

        List<PriceTick> ticks = priceTickMapper.selectList(
            new LambdaQueryWrapper<PriceTick>()
                .eq(PriceTick::getStockId, stockId)
                .between(PriceTick::getTickTime, startDate.atTime(15, 0), endDate.atTime(15, 0))
                .orderByDesc(PriceTick::getTickTime)
        );

        Map<LocalDate, BigDecimal> closeMap = new HashMap<>();
        for (PriceTick tick : ticks) {
            LocalDate tickDate = tick.getTickTime().toLocalDate();
            if (!closeMap.containsKey(tickDate)) {
                closeMap.put(tickDate, tick.getPrice());
            }
        }

        List<Map<String, Object>> result = new ArrayList<>();
        closeMap.forEach((date, price) -> {
            Map<String, Object> item = new HashMap<>();
            item.put("date", date);
            item.put("close", price);
            result.add(item);
        });

        result.sort(Comparator.comparing(m -> ((LocalDate) m.get("date"))));
        return result;
    }

    @Override
    public Map<String, Object> getRealtimeQuote(Long stockId, LocalDate date, LocalTime time) {
        // 获取当前tick
        String tickKey = String.format("tick:%s:%d", date, stockId);
        String tickValue = (String) redisTemplate.opsForHash().get(tickKey, time.toString());
        if (tickValue == null) return null;

        String[] parts = tickValue.split(",");
        BigDecimal price = new BigDecimal(parts[0]);
        long volume = Long.parseLong(parts[1]);

        // 获取当日汇总
        String dailyKey = String.format("stock:daily:%s:%d", date, stockId);
        Map<Object, Object> daily = redisTemplate.opsForHash().entries(dailyKey);

        BigDecimal open = new BigDecimal(daily.getOrDefault("open", price).toString());
        BigDecimal high = new BigDecimal(daily.getOrDefault("high", price).toString());
        BigDecimal low = new BigDecimal(daily.getOrDefault("low", price).toString());
        BigDecimal prevClose = new BigDecimal(daily.getOrDefault("prevClose", price).toString());

        // 更新最高最低价
        if (price.compareTo(high) > 0) {
            redisTemplate.opsForHash().put(dailyKey, "high", price.toString());
            high = price;
        }
        if (price.compareTo(low) < 0) {
            redisTemplate.opsForHash().put(dailyKey, "low", price.toString());
            low = price;
        }
        redisTemplate.opsForHash().put(dailyKey, "last", price.toString());

        Map<String, Object> result = new HashMap<>();
        result.put("stockId", stockId);
        result.put("price", price);
        result.put("volume", volume);
        result.put("time", time.toString());
        result.put("open", open);
        result.put("high", high);
        result.put("low", low);
        result.put("prevClose", prevClose);
        return result;
    }

    private List<PriceTick> generateStockTicks(Stock stock, LocalDate date) {
        List<PriceTick> ticks = new ArrayList<>();

        // AI生成开盘价+GBM参数
        Company company = companyService.getById(stock.getCompanyId());
        GbmParams params = generateGbmParams(stock, company);

        // 把开盘价存到Stock表
        BigDecimal openPrice = BigDecimal.valueOf(params.openPrice).setScale(2, RoundingMode.HALF_UP);
        stock.setOpen(openPrice);
        stockService.updateById(stock);
        log.info("更新股票{}开盘价: {}", stock.getCode(), openPrice);

        // 固定参数：240分钟，每10秒一个点，共1440个点
        int steps = 1440;
        double dt = 240.0 / (steps * 252 * 240);

        // GBM生成价格序列
        double[] prices = new double[steps];
        prices[0] = params.openPrice;
        Random random = new Random();

        for (int i = 1; i < steps; i++) {
            double z = random.nextGaussian();
            prices[i] = prices[i - 1] * Math.exp(
                (params.mu - 0.5 * params.sigma * params.sigma) * dt
                + params.sigma * Math.sqrt(dt) * z
            );
        }

        // 转换为PriceTick
        for (int i = 0; i < steps; i++) {
            LocalTime time = calculateTickTime(i);
            LocalDateTime tickTime = date.atTime(time);

            long volume = (long) (15000 + Math.random() * 8000);

            PriceTick tick = new PriceTick();
            tick.setStockId(stock.getId());
            tick.setPrice(BigDecimal.valueOf(prices[i]).setScale(2, RoundingMode.HALF_UP));
            tick.setVolume(volume);
            tick.setTickTime(tickTime);
            ticks.add(tick);
        }

        return ticks;
    }

    private GbmParams generateGbmParams(Stock stock, Company company) {
        String prompt = String.format("""
            你是股票行情模拟器。根据以下股票和公司信息，生成今日开盘价和GBM参数。

            【股票信息】
            - 代码: %s
            - 名称: %s
            - 昨收价: %.2f

            【公司信息】
            - 公司: %s
            - 行业: %s
            - 简介: %s
            - 市值: %.0f
            - 市盈率: %.2f

            【GBM公式】
            S(t+dt) = S(t) × exp((μ - ½σ²)dt + σ√dt·Z)

            【要求】
            1. openPrice: 预估开盘价，通常在昨收价±2%%范围内波动
            2. mu: 年化收益率（漂移率），-0.3到0.5
            3. sigma: 年化波动率，0.1到0.8（科技股偏高，金融股偏低）

            只返回JSON，无其他内容：
            {"openPrice": xx.xx, "mu": 0.xx, "sigma": 0.xx}
            """,
            stock.getCode(),
            stock.getName(),
            stock.getPrevClose().doubleValue(),
            company.getName(),
            company.getIndustry(),
            company.getDescription(),
            company.getMarketCap().doubleValue(),
            company.getPeRatio().doubleValue()
        );

        try {
            String response = aiService.chat(prompt);
            String json = response.replaceAll("(?s).*?(\\{.*?\\}).*", "$1");
            cn.hutool.json.JSONObject obj = cn.hutool.json.JSONUtil.parseObj(json);

            double openPrice = obj.getDouble("openPrice");
            double mu = obj.getDouble("mu");
            double sigma = obj.getDouble("sigma");

            log.info("AI生成参数 - {}: openPrice={}, mu={}, sigma={}",
                stock.getCode(), openPrice, mu, sigma);
            return new GbmParams(openPrice, mu, sigma);
        } catch (Exception e) {
            log.warn("AI生成参数失败，使用默认值: {}", e.getMessage());
            double defaultOpen = stock.getPrevClose().doubleValue();
            return new GbmParams(defaultOpen, 0.1, 0.3);
        }
    }

    private record GbmParams(double openPrice, double mu, double sigma) {}

    private LocalTime calculateTickTime(int index) {
        int morningTicks = 720; // 120分钟 × 6
        if (index < morningTicks) {
            int seconds = index * 10;
            return LocalTime.of(9, 30).plusSeconds(seconds);
        } else {
            int afternoonIndex = index - morningTicks;
            int seconds = afternoonIndex * 10;
            return LocalTime.of(13, 0).plusSeconds(seconds);
        }
    }
}
