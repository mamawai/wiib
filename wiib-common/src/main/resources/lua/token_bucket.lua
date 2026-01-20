-- 令牌桶算法实现
-- KEYS[1]: 限流器键名
-- ARGV[1]: 每秒允许的令牌数
-- ARGV[2]: 桶容量
-- ARGV[3]: 当前时间戳（毫秒）
-- ARGV[4]: 请求的令牌数（默认为1）

local key = KEYS[1]
local permits_per_second = tonumber(ARGV[1])
local capacity = tonumber(ARGV[2])
local now = tonumber(ARGV[3])
local requested_permits = tonumber(ARGV[4]) or 1

-- 从Redis hash中获取当前限流信息
local rate_limit_info = redis.call('HMGET', key, 'last_refill_time', 'tokens')
local last_refill_time = tonumber(rate_limit_info[1])
local tokens = tonumber(rate_limit_info[2])

-- 如果是第一次请求，初始化
if last_refill_time == nil or tokens == nil then
    last_refill_time = now
    tokens = capacity
else
    -- 处理可能的时钟偏差（时间倒退）
    if now >= last_refill_time then
        -- 计算自上次补充以来的时间（将毫秒转换为秒）
        local elapsed_time = (now - last_refill_time) / 1000.0

        -- 计算要添加的新令牌数（但不超过容量）
        tokens = math.min(capacity, tokens + elapsed_time * permits_per_second)
    end
    -- 如果 now < last_refill_time，保持当前令牌数
end

-- 检查是否有足够的令牌
local allowed = tokens >= requested_permits

if allowed then
    -- 消费请求的令牌
    tokens = tokens - requested_permits

    -- 更新Redis中的桶状态
    redis.call('HSET', key, 'last_refill_time', now, 'tokens', tokens)

    -- 设置过期时间 向上取整
    local ttl = math.ceil(capacity / permits_per_second * 2)
    redis.call('EXPIRE', key, ttl)

    return 1  -- 请求被允许
else
    -- 请求被拒绝：不更新Redis
    local ttl = math.ceil(capacity / permits_per_second * 2)
    redis.call('EXPIRE', key, ttl)

    return 0  -- 请求被拒绝
end
