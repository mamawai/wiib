package com.mawai.wiibservice.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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

    /**
     * 获取指定时间点的tick数据
     * @return {price, volume}
     */
    public Map<String, Object> getTickData(Long stockId, LocalDate date, LocalTime time) {
        String tickKey = String.format("tick:%s:%d", date, stockId);
        String value = (String) stringRedisTemplate.opsForHash().get(tickKey, time.toString());

        if (value == null) return null;

        String[] parts = value.split(",");
        Map<String, Object> result = new HashMap<>();
        result.put("price", new BigDecimal(parts[0]));
        result.put("volume", Long.parseLong(parts[1]));
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

    public boolean delete(String key) {
        return Boolean.TRUE.equals(stringRedisTemplate.unlink(key));
    }

    public boolean hasKey(String key) {
        return Boolean.TRUE.equals(stringRedisTemplate.hasKey(key));
    }

    public boolean setIfAbsent(String key, String value, long timeout, TimeUnit unit) {
        return Boolean.TRUE.equals(stringRedisTemplate.opsForValue().setIfAbsent(key, value, timeout, unit));
    }

    public boolean expire(String key, long timeout, TimeUnit unit) {
        return Boolean.TRUE.equals(stringRedisTemplate.expire(key, timeout, unit));
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
}
