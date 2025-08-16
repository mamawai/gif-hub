package com.mawai.ghcommon.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * 缓存服务 - 封装Redis操作
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CacheService {

    private final RedisTemplate<String, Object> redisTemplate;

    /**
     * 复制key
     * @param sourceKey 源key
     * @param targetKey 目标key
     */
    public void copy(String sourceKey, String targetKey) {
        redisTemplate.copy(sourceKey, targetKey, false);
        redisTemplate.expire(targetKey, 10, TimeUnit.SECONDS);
    }

    /**
     * 存储值到缓存
     * @param key 键
     * @param value 值
     */
    public void set(String key, Object value) {
        redisTemplate.opsForValue().set(key, value);
    }

    /**
     * 存储值到缓存并设置过期时间
     * @param key 键
     * @param value 值
     * @param timeout 过期时间
     * @param unit 时间单位
     */
    public void set(String key, Object value, long timeout, TimeUnit unit) {
        redisTemplate.opsForValue().set(key, value, timeout, unit);
    }

    /**
     * 存储值到缓存并设置过期时间
     * @param key 键
     * @param value 值
     * @param duration 过期时间
     */
    public void set(String key, Object value, Duration duration) {
        redisTemplate.opsForValue().set(key, value, duration);
    }

    /**
     * 从缓存获取值
     * @param key 键
     * @return 值
     */
    public <T> T get(String key) {
        return (T) redisTemplate.opsForValue().get(key);
    }

    /**
     * 判断键是否存在
     * @param key 键
     * @return 是否存在
     */
    public boolean hasKey(String key) {
        return Boolean.TRUE.equals(redisTemplate.hasKey(key));
    }

    /**
     * 删除键
     * @param key 键
     */
    public boolean delete(String key) {
        return Boolean.TRUE.equals(redisTemplate.delete(key));
    }

    /**
     * 获取键过期时间
     * @param key 键
     * @return 过期时间（秒）
     */
    public Long getExpire(String key) {
        return redisTemplate.getExpire(key);
    }

    /**
     * 设置键过期时间
     * @param key 键
     * @param timeout 过期时间
     * @param unit 时间单位
     * @return 是否成功
     */
    public boolean expire(String key, long timeout, TimeUnit unit) {
        return Boolean.TRUE.equals(redisTemplate.expire(key, timeout, unit));
    }

    /**
     * 设置键过期时间
     * @param key 键
     * @param duration 过期时间
     * @return 是否成功
     */
    public boolean expire(String key, Duration duration) {
        return Boolean.TRUE.equals(redisTemplate.expire(key, duration));
    }

    /**
     * 向List右侧添加元素
     * @param key 键
     * @param values 值
     * @return 添加后List长度
     */
    public Long rightPushAll(String key, Object... values) {
        return redisTemplate.opsForList().rightPushAll(key, values);
    }
    
    /**
     * 获取List大小
     * @param key 键
     * @return List大小
     */
    public Long listSize(String key) {
        return redisTemplate.opsForList().size(key);
    }

    /**
     * 获取List指定索引元素
     * @param key 键
     * @param index 索引
     * @return 元素
     */
    public <T> T listIndex(String key, long index) {
        return (T) redisTemplate.opsForList().index(key, index);
    }

    /**
     * 设置状态字符串
     * @param key 键
     * @param status 状态
     * @param timeout 过期时间
     * @param unit 时间单位
     */
    public void setStatus(String key, String status, long timeout, TimeUnit unit) {
        redisTemplate.opsForValue().set(key, status, timeout, unit);
    }

    /**
     * 仅当键不存在时设置值（实现分布式锁）
     * @param key 键
     * @param value 值
     * @param timeout 过期时间
     * @param unit 时间单位
     * @return 是否设置成功
     */
    public boolean setIfAbsent(String key, Object value, long timeout, TimeUnit unit) {
        return Boolean.TRUE.equals(redisTemplate.opsForValue().setIfAbsent(key, value, timeout, unit));
    }

    /**
     * 获取List指定范围元素
     * @param key 键
     * @param start 开始索引
     * @param end 结束索引
     * @return 元素列表
     */
    public <T> List<T> listRange(String key, long start, long end) {
        return (List<T>) redisTemplate.opsForList().range(key, start, end);
    }
    
    /**
     * 递增操作
     * @param key 键
     * @return 递增后的值
     */
    public Long increment(String key) {
        return redisTemplate.opsForValue().increment(key);
    }
    
    /**
     * 递增指定数量
     * @param key 键
     * @param delta 增量
     * @return 递增后的值
     */
    public Long increment(String key, long delta) {
        return redisTemplate.opsForValue().increment(key, delta);
    }
    
    /**
     * 递增指定数量（浮点数）
     * @param key 键
     * @param delta 增量
     * @return 递增后的值
     */
    public Double increment(String key, double delta) {
        return redisTemplate.opsForValue().increment(key, delta);
    }

    /** 
     * 递增指定数量并设置过期时间--lua实现
     * @param key 键
     * @param delta 增量
     * @param timeout 过期时间
     * @param unit 时间单位    
     */
    public void increment(String key, long delta, long timeout, TimeUnit unit) {
        // 使用Lua脚本确保原子性操作
        String scriptText = "local value = redis.call('INCRBY', KEYS[1], ARGV[1]); " +
                        "redis.call('EXPIRE', KEYS[1], ARGV[2]); " +
                        "return value;";
        RedisScript<Long> script = RedisScript.of(scriptText, Long.class);

        // 使用Object数组传递参数 --- 真的坑啊 需要用Object传 TnT 改了一天
        Object[] args = new Object[] {delta, unit.toSeconds(timeout)};
        redisTemplate.execute(script, List.of(key), args);
    }
    
    /**
     * 递增并处理set中的元素：
     * 如果在dislike字段中存在，则从dislike中移除；
     * 如果dislike中不存在，则添加到like字段
     *
     * @param countKey  计数键
     * @param memberKey set键
     * @param fileId    要添加到集合的值
     * @param timeout   过期时间
     * @param unit      时间单位
     */
    public void incrementAndAddToSet(String countKey, String memberKey, String fileId, long timeout, TimeUnit unit) {
        // 使用Lua脚本确保原子性操作
        String scriptText = "local value = redis.call('INCR', KEYS[1]); " +
                        "redis.call('EXPIRE', KEYS[1], ARGV[3]); " +

                        // 检查用户是否已将此GIF标记为不喜欢
                        "if redis.call('SISMEMBER', KEYS[2] .. ':dislike', ARGV[2]) == 1 then " +
                        "    redis.call('SREM', KEYS[2] .. ':dislike', ARGV[2]); " +
                        "    redis.call('EXPIRE', KEYS[2] .. ':dislike', ARGV[3]); " +
                        "else " +
                        "    redis.call('SADD', KEYS[2] .. ':like', ARGV[2]); " +
                        "    redis.call('EXPIRE', KEYS[2] .. ':like', ARGV[3]); " +
                        "end " +
                        "return value;";
        RedisScript<Long> script = RedisScript.of(scriptText, Long.class);
        
        // 使用Object数组传递参数
        Object[] args = new Object[] {"like", fileId, unit.toSeconds(timeout)};
        redisTemplate.execute(script, List.of(countKey, memberKey), args);
    }
    
    /**
     * 递减操作
     * @param key 键
     * @return 递减后的值
     */
    public Long decrement(String key) {
        return redisTemplate.opsForValue().decrement(key);
    }
    
    /**
     * 递减指定数量
     * @param key 键
     * @param delta 减量
     * @return 递减后的值
     */
    public Long decrement(String key, long delta) {
        return redisTemplate.opsForValue().decrement(key, delta);
    }

    /** 
     * 递减指定数量并设置过期时间--lua实现
     * @param key 键
     * @param delta 减量
     * @param timeout 过期时间
     * @param unit 时间单位
     */
    public void decrement(String key, long delta, long timeout, TimeUnit unit) {
        // 使用Lua脚本确保原子性操作
        String scriptText = "local value = redis.call('DECRBY', KEYS[1], ARGV[1]); " +
                        "redis.call('EXPIRE', KEYS[1], ARGV[2]); " +
                        "return value;";
        RedisScript<Long> script = RedisScript.of(scriptText, Long.class);
        
        // 使用Object数组传递参数
        Object[] args = new Object[] {delta, unit.toSeconds(timeout)};
        redisTemplate.execute(script, List.of(key), args);
    }   
    
    /**
     * 递减并处理set中的元素：
     * 如果在like字段中存在，则从like中移除；
     * 如果like中不存在，则添加到dislike字段
     *
     * @param countKey  计数键
     * @param memberKey set键
     * @param fileId    要设置的元素
     * @param timeout   过期时间
     * @param unit      时间单位
     */
    public void decrementAndRemoveFromSet(String countKey, String memberKey, String fileId, long timeout, TimeUnit unit) {
        // 使用Lua脚本确保原子性操作
        String scriptText = "local value = redis.call('DECR', KEYS[1]); " +
                        "redis.call('EXPIRE', KEYS[1], ARGV[3]); " +
                        "if redis.call('SISMEMBER', KEYS[2] .. ':like', ARGV[2]) == 1 then " +
                        "    redis.call('SREM', KEYS[2] .. ':like', ARGV[2]); " +
                        "    redis.call('EXPIRE', KEYS[2] .. ':like', ARGV[3]); " +
                        "else " +
                        "    redis.call('SADD', KEYS[2] .. ':dislike', ARGV[2]); " +
                        "    redis.call('EXPIRE', KEYS[2] .. ':dislike', ARGV[3]); " +
                        "end " +
                        "return value;";
        RedisScript<Long> script = RedisScript.of(scriptText, Long.class);  
        
        // 使用Object数组传递参数
        Object[] args = new Object[] {"dislike", fileId, unit.toSeconds(timeout)};
        redisTemplate.execute(script, List.of(countKey, memberKey), args);
    }
    
    /**
     * 获取计数器值，不存在则初始化为0
     * @param key 键
     * @return 计数器值
     */
    public Long getCounter(String key) {
        if (!hasKey(key)) {
            set(key, 0L);
            return 0L;
        }
        return get(key);
    }
    
    /**
     * 获取计数器值并设置过期时间，不存在则初始化为0
     * @param key 键
     * @param timeout 过期时间
     * @param unit 时间单位
     * @return 计数器值
     */
    public Long getCounterWithExpire(String key, long timeout, TimeUnit unit) {
        if (!hasKey(key)) {
            set(key, 0L, timeout, unit);
            return 0L;
        }
        expire(key, timeout, unit);
        return get(key);
    }

    /**
     * 获取set集合中的所有元素
     * @param key 键
     * @return 元素列表
     */
    public Set<Object> getSet(String key) {
        return redisTemplate.opsForSet().members(key);
    }

    /**
     * 获取set集合中的所有元素并转换为String类型
     * @param key 键
     * @return String类型的元素集合
     */
    public Set<String> getStringSet(String key) {
        Set<Object> objectSet = redisTemplate.opsForSet().members(key);
        if (objectSet == null) {
            return Set.of();
        }
        return objectSet.stream()
                .map(Object::toString)
                .collect(java.util.stream.Collectors.toSet());
    }
    
    /**
     * 根据模式获取所有匹配的键
     * @param pattern 键的模式，如"user:*"
     * @return 匹配的键的集合
     */
    public Set<String> getKeysWithPattern(String pattern) {
        return redisTemplate.keys(pattern);
    }
    
    /**
     * 获取值并重置为0（原子操作）
     * @param key 键
     * @return 获取到的值，如果不存在则返回null
     */
    public Long getAndReset(String key) {
        // 原子操作，获取值并设置为0
        String script = "local value = redis.call('GET', KEYS[1]); " +
                "redis.call('SET', KEYS[1], 0); " +
                "return value;";
        Object result = redisTemplate.execute(RedisScript.of(script, String.class), List.of(key));
        return Long.valueOf(result.toString());
    }

    /**
     * 获取所有有like或dislike数据的用户ID
     * @param userLikeKeyPrefix 用户喜欢键前缀，如"user:like:"
     * @return 用户ID集合
     */
    public Set<String> getUserIdsWithLikeData(String userLikeKeyPrefix) {
        Set<String> likeKeys = getKeysWithPattern(userLikeKeyPrefix + "*:like");
        Set<String> dislikeKeys = getKeysWithPattern(userLikeKeyPrefix + "*:dislike");

        Set<String> userIds = new HashSet<>();
        int prefixLength = userLikeKeyPrefix.length();

        // 从like keys中提取用户ID
        for (String key : likeKeys) {
            if (key.length() > prefixLength + 5) { // 5 = ":like".length()
                String userId = key.substring(prefixLength, key.length() - 5);
                userIds.add(userId);
            }
        }

        // 从dislike keys中提取用户ID
        for (String key : dislikeKeys) {
            if (key.length() > prefixLength + 8) { // 8 = ":dislike".length()
                String userId = key.substring(prefixLength, key.length() - 8);
                userIds.add(userId);
            }
        }

        return userIds;
    }

    /**
     * 原子获取set中like和dislike字段的值
     * @param key set键
     * @param clear 是否清空set
     * @return 包含like和dislike字段值的Map
     */
    public Map<String, Set<String>> getSetLikeDislike(String key, boolean clear) {
        String scriptText = "local result = {}; " +
                    "result['like'] = redis.call('SMEMBERS', KEYS[1] .. ':like'); " +
                    "result['dislike'] = redis.call('SMEMBERS', KEYS[1] .. ':dislike'); " +
                    // 如果clear为true，则清空集合
                    "if tonumber(ARGV[1]) == 1 then " +
                    "    redis.call('DEL', KEYS[1] .. ':like', KEYS[1] .. ':dislike'); " +
                    "end " +
                    "return cjson.encode(result);";
        RedisScript<String> script = RedisScript.of(scriptText, String.class);
        
        // 使用Object数组传递参数
        Object[] args = new Object[] {clear ? "1" : "0"};
        String result = redisTemplate.execute(script, List.of(key), args);
        
        Map<String, Set<String>> resultMap = new HashMap<>();
        // 默认初始化空集合
        resultMap.put("like", new HashSet<>());
        resultMap.put("dislike", new HashSet<>());
        
        if (result != null) {
            try {
                // 使用Jackson解析JSON字符串
                Map<String, Object> parsedResult = new ObjectMapper().readValue(result, new TypeReference<Map<String, Object>>() {});
                
                // 处理like字段
                if (parsedResult.get("like") != null && parsedResult.get("like") instanceof List) {
                    @SuppressWarnings("unchecked")
                    List<String> likeList = (List<String>) parsedResult.get("like");
                    resultMap.put("like", new HashSet<>(likeList));
                }
                
                // 处理dislike字段
                if (parsedResult.get("dislike") != null && parsedResult.get("dislike") instanceof List) {
                    @SuppressWarnings("unchecked")
                    List<String> dislikeList = (List<String>) parsedResult.get("dislike");
                    resultMap.put("dislike", new HashSet<>(dislikeList));
                }
                // 返回set也好 contains效率高
            } catch (Exception e) {
                log.error("解析Redis返回的JSON失败: {}", e.getMessage());
            }
        }
        
        return resultMap;
    }
}