package com.mawai.wiibservice.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * 缓存服务 - 封装Redis操作
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CacheService {

    private final RedisTemplate<String, Object> redisTemplate;
    private final StringRedisTemplate stringRedisTemplate;

    // ==================== 实时行情 ====================

    /**
     * 获取股票实时价格
     * @return 当前价格，无数据返回null
     */
    public BigDecimal getCurrentPrice(Long stockId) {
        LocalDate today = LocalDate.now();
        String dailyKey = String.format("stock:daily:%s:%d", today, stockId);
        String last = (String) stringRedisTemplate.opsForHash().get(dailyKey, "last");
        return last != null ? new BigDecimal(last) : null;
    }

    /**
     * 批量获取股票实时价格
     * @return stockId -> price，无数据的stockId不在map中
     */
    public Map<Long, BigDecimal> getCurrentPrices(List<Long> stockIds) {
        if (stockIds == null || stockIds.isEmpty()) {
            return Map.of();
        }
        LocalDate today = LocalDate.now();

        // 构建所有key
        List<String> keys = stockIds.stream()
                .map(id -> String.format("stock:daily:%s:%d", today, id))
                .toList();

        // Pipeline批量获取
        List<Object> results = stringRedisTemplate.executePipelined(
                (org.springframework.data.redis.core.RedisCallback<Object>) connection -> {
                    for (String key : keys) {
                        connection.hashCommands().hGet(key.getBytes(), "last".getBytes());
                    }
                    return null;
                }
        );

        Map<Long, BigDecimal> priceMap = new HashMap<>();
        for (int i = 0; i < stockIds.size(); i++) {
            Object result = results.get(i);
            if (result != null) {
                priceMap.put(stockIds.get(i), new BigDecimal(result.toString()));
            }
        }
        return priceMap;
    }

    /**
     * 获取股票当日行情汇总
     * @return {open, high, low, last, prevClose}
     */
    public Map<String, BigDecimal> getDailyQuote(Long stockId) {
        LocalDate today = LocalDate.now();
        String dailyKey = String.format("stock:daily:%s:%d", today, stockId);
        Map<Object, Object> raw = stringRedisTemplate.opsForHash().entries(dailyKey);

        if (raw.isEmpty()) return null;

        Map<String, BigDecimal> result = new HashMap<>();
        raw.forEach((k, v) -> result.put(k.toString(), new BigDecimal(v.toString())));
        return result;
    }

    // ==================== 通用缓存 ====================

    public void set(String key, String value, long timeout, TimeUnit unit) {
        stringRedisTemplate.opsForValue().set(key, value, timeout, unit);
    }

    public void set(String key, String value, Duration duration) {
        stringRedisTemplate.opsForValue().set(key, value, duration);
    }

    public String get(String key) {
        return stringRedisTemplate.opsForValue().get(key);
    }

    public void delete(String key) {
        stringRedisTemplate.unlink(key);
    }

    public boolean hasKey(String key) {
        return stringRedisTemplate.hasKey(key);
    }

    public boolean setIfAbsent(String key, String value, long timeout, TimeUnit unit) {
        return Boolean.TRUE.equals(stringRedisTemplate.opsForValue().setIfAbsent(key, value, timeout, unit));
    }

    public void expire(String key, long timeout, TimeUnit unit) {
        stringRedisTemplate.expire(key, timeout, unit);
    }

    // ==================== Hash操作 ====================

    public void hSet(String key, String field, String value) {
        stringRedisTemplate.opsForHash().put(key, field, value);
    }

    public String hGet(String key, String field) {
        Object value = stringRedisTemplate.opsForHash().get(key, field);
        return value != null ? value.toString() : null;
    }

    public Map<String, String> hGetAll(String key) {
        Map<Object, Object> raw = stringRedisTemplate.opsForHash().entries(key);
        Map<String, String> result = new HashMap<>();
        raw.forEach((k, v) -> result.put(k.toString(), v.toString()));
        return result;
    }

    public void hSetAll(String key, Map<String, String> hash) {
        stringRedisTemplate.opsForHash().putAll(key, hash);
    }

    // ==================== 对象序列化（使用RedisTemplate） ====================

    public void setObject(String key, Object obj, long timeout, TimeUnit unit) {
        redisTemplate.opsForValue().set(key, obj, timeout, unit);
    }

    @SuppressWarnings("unchecked")
    public <T> T getObject(String key) {
        return (T) redisTemplate.opsForValue().get(key);
    }

    @SuppressWarnings("unchecked")
    public <T> List<T> getList(String key) {
        return (List<T>) redisTemplate.opsForValue().get(key);
    }

    // ==================== Set操作 ====================

    /**
     * 添加元素到Set
     */
    public void sAdd(String key, String... values) {
        stringRedisTemplate.opsForSet().add(key, values);
    }

    /**
     * 从Set中移除元素
     */
    public Long sRemove(String key, Object... values) {
        return stringRedisTemplate.opsForSet().remove(key, values);
    }

    /**
     * 获取Set的所有成员
     */
    public Set<String> sMembers(String key) {
        return stringRedisTemplate.opsForSet().members(key);
    }

    /**
     * 判断元素是否在Set中
     */
    public Boolean sIsMember(String key, Object value) {
        return stringRedisTemplate.opsForSet().isMember(key, value);
    }

    /**
     * 获取Set的大小
     */
    public Long sSize(String key) {
        return stringRedisTemplate.opsForSet().size(key);
    }

    /**
     * 多个Set的交集
     */
    public Set<String> sIntersect(String key, String otherKey) {
        return stringRedisTemplate.opsForSet().intersect(key, otherKey);
    }

    /**
     * 多个Set的并集
     */
    public Set<String> sUnion(String key, String otherKey) {
        return stringRedisTemplate.opsForSet().union(key, otherKey);
    }

    /**
     * 多个Set的差集
     */
    public Set<String> sDifference(String key, String otherKey) {
        return stringRedisTemplate.opsForSet().difference(key, otherKey);
    }

    // ==================== 通用Key操作 ====================

    /**
     * 删除多个key
     */
    public Long deleteKeys(Collection<String> keys) {
        return stringRedisTemplate.delete(keys);
    }

    /**
     * 批量删除key（通配符）
     * 使用SCAN迭代+UNLINK异步删除，避免阻塞Redis
     */
    public void deletePattern(String pattern) {
        Set<String> keys = scanKeys(pattern, 200);
        if (!keys.isEmpty()) {
            stringRedisTemplate.unlink(keys);
        }
    }

    /**
     * 查找匹配的key（使用SCAN迭代，避免KEYS阻塞）
     */
    public Set<String> scanKeys(String pattern, int count) {
        Set<String> keys = new HashSet<>();
        try (Cursor<String> cursor = stringRedisTemplate.scan(
                ScanOptions.scanOptions()
                        .match(pattern)
                        .count(count)
                        .build())) {
            cursor.forEachRemaining(keys::add);
        } catch (Exception e) {
            log.error("SCAN keys failed: pattern={}, count={}, error={}",
                    pattern, count, e.getMessage(), e);
            throw new RuntimeException("SCAN keys failed: " + e.getMessage(), e);
        }
        return keys;
    }

    /**
     * 检查多个key是否存在
     */
    public Long countExistingKeys(Collection<String> keys) {
        return stringRedisTemplate.countExistingKeys(keys);
    }
}
