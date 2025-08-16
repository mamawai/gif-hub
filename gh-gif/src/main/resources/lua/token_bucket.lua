-- Token Bucket Algorithm Implementation
-- KEYS[1]: rate limiter key
-- ARGV[1]: permits per second
-- ARGV[2]: capacity
-- ARGV[3]: current timestamp (milliseconds)
-- ARGV[4]: requested permits (default 1)

local key = KEYS[1]
local permits_per_second = tonumber(ARGV[1])
local capacity = tonumber(ARGV[2])
local now = tonumber(ARGV[3])
local requested_permits = tonumber(ARGV[4]) or 1

-- Get current rate limit info from Redis hash
local rate_limit_info = redis.call('HMGET', key, 'last_refill_time', 'tokens')
local last_refill_time = tonumber(rate_limit_info[1])
local tokens = tonumber(rate_limit_info[2])

-- Initialize bucket if this is the first request
if last_refill_time == nil or tokens == nil then
    last_refill_time = now
    tokens = capacity
else
    -- Calculate time elapsed since last refill (convert milliseconds to seconds)
    local elapsed_time = (now - last_refill_time) / 1000.0
    
    -- Calculate new tokens to add (but don't exceed capacity)
    local new_tokens = math.min(capacity, tokens + elapsed_time * permits_per_second)
    tokens = new_tokens
end

-- Check if we have enough tokens
local allowed = tokens >= requested_permits

if allowed then
    -- Consume the requested tokens
    tokens = tokens - requested_permits
    
    -- Update the bucket state in Redis
    redis.call('HMSET', key, 'last_refill_time', now, 'tokens', tokens)
    
    -- Set expiration time (2 times the time to fill the bucket)
    local ttl = math.ceil(capacity / permits_per_second * 2)
    redis.call('EXPIRE', key, ttl)
    
    return 1  -- Request allowed
else
    -- Update last refill time but don't consume tokens
    redis.call('HMSET', key, 'last_refill_time', now, 'tokens', tokens)
    
    -- Set expiration time
    local ttl = math.ceil(capacity / permits_per_second * 2)
    redis.call('EXPIRE', key, ttl)
    
    return 0  -- Request denied
end

