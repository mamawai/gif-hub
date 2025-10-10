package com.mawai.ghcommon.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.Limit;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
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
    private final StringRedisTemplate stringRedisTemplate;

    // 避免每次调用都创建新实例
    private final ObjectMapper objectMapper = createObjectMapper();

    private static ObjectMapper createObjectMapper() {
        return new ObjectMapper();
    }

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
     * 获取数字类型的值
     * @param key 键
     * @return 值
     */
    public <T> T getNumber(String key) {
        try {
            Object value = redisTemplate.opsForValue().get(key);
            if (value instanceof Number) {
                return (T) value;
            }
            if (value ==  null) {
                return null;
            }
            log.warn("{} 的值不是数字类型", key);
            return null;
        } catch (Exception e) {
            log.error("getNumber获取值失败: {}", e.getMessage(), e);
            return null;
        }
    }

    /**
     * 批量获取数字类型的值
     * @param keys 键列表
     * @return 按keys顺序返回的数字值列表（不存在或非数字的位置为0）
     */
    public List<Long> batchGetNumbers(List<String> keys) {
        List<Long> result = new ArrayList<>(keys.size());
        
        if (keys.isEmpty()) {
            return result;
        }
        
        try {
            // 使用Redis的批量获取操作，返回结果与keys顺序一致
            List<Object> values = redisTemplate.opsForValue().multiGet(keys);
            
            if (values != null) {
                for (int i = 0; i < keys.size(); i++) {
                    Object value = values.get(i);
                    if (value instanceof Number) {
                        result.add(((Number) value).longValue());
                    } else {
                        result.add(0L);
                    }
                }
            }
        } catch (Exception e) {
            log.error("批量获取数字值失败: {}", e.getMessage(), e);
        }
        
        return result;
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
        String scriptText = "redis.call('INCRBY', KEYS[1], ARGV[1]); " +
                        "redis.call('EXPIRE', KEYS[1], ARGV[2]);";

        // 使用stringRedisTemplate避免序列化问题，参数必须是字符串
        stringRedisTemplate.execute(
            RedisScript.of(scriptText, Void.class),
            List.of(key),
            String.valueOf(delta),
            String.valueOf(unit.toSeconds(timeout))
        );
    }

    /**
     * 获取用户的所有点赞分类ID映射
     * @param categoryKey 分类hash键（如：user:like:category:userId）
     * @param clearAfterGet 获取后是否清空
     * @return GIF ID -> 分类ID 的映射
     */
    private Map<String, Long> getLikeCategoryIds(String categoryKey, boolean clearAfterGet) {
        try {
            Map<Object, Object> rawMap = redisTemplate.opsForHash().entries(categoryKey);
            if (clearAfterGet) redisTemplate.delete(categoryKey);
            
            // 转换类型
            Map<String, Long> result = new HashMap<>();
            for (Map.Entry<Object, Object> entry : rawMap.entrySet()) {
                String gifId = entry.getKey().toString();
                Long categoryId = Long.parseLong(entry.getValue().toString());
                result.put(gifId, categoryId);
            }
            
            log.info("获取点赞分类ID映射: key={}, size={}", categoryKey, result.size());
            return result;
        } catch (Exception e) {
            log.error("获取点赞分类ID映射失败: categoryKey={}, 错误: {}", categoryKey, e.getMessage(), e);
            return new HashMap<>();
        }
    }

    /**
     * 安全获取用户的所有点赞分类ID映射，支持oldKey备份机制
     * @param categoryKey 分类hash键（如：user:like:category:userId）
     * @param clearAfterGet 获取后是否清空
     * @return GIF ID -> 分类ID 的映射
     */
    public Map<String, Long> getLikeCategoryIdsSafely(String categoryKey, boolean clearAfterGet) {
        try {
            // 先创建备份，再获取并清空（确保数据安全）
            if (clearAfterGet && hasKey(categoryKey)) {
                String oldCategoryKey = categoryKey + ":old";
                copy(categoryKey, oldCategoryKey);
            }
            
            return getLikeCategoryIds(categoryKey, clearAfterGet);
        } catch (Exception e) {
            log.error("安全获取点赞分类ID映射失败: categoryKey={}, 错误: {}", categoryKey, e.getMessage(), e);
            return new HashMap<>();
        }
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
        String scriptText = "redis.call('DECRBY', KEYS[1], ARGV[1]); " +
                        "redis.call('EXPIRE', KEYS[1], ARGV[2]);";
        
        stringRedisTemplate.execute(
            RedisScript.of(scriptText, Void.class), 
            List.of(key), 
            String.valueOf(delta),
            String.valueOf(unit.toSeconds(timeout))
        );
    }   
    
    /**
     * 新的点赞操作（使用Hash+Set混合结构）
     * @param countKey 计数键 gif:like:fileId
     * @param likeHashKey 点赞Hash键 user:like:category:userId  
     * @param dislikeSetKey 不喜欢Set键 user:dislike:userId
     * @param fileId 文件ID
     * @param categoryId 分类ID
     * @param timeout 过期时间
     * @param unit 时间单位
     */
    public void likeOperationOptimized(String countKey, String likeHashKey, String dislikeSetKey, String fileId, Long categoryId, long timeout, TimeUnit unit) {
        String script = 
            "if redis.call('SISMEMBER', KEYS[3], ARGV[1]) == 1 then " +
            "    redis.call('SREM', KEYS[3], ARGV[1]) " +
            "end " +
            "redis.call('HSET', KEYS[2], ARGV[1], ARGV[2]) " +
            "redis.call('INCR', KEYS[1]) " +
            "redis.call('EXPIRE', KEYS[1], ARGV[3]) " +
            "redis.call('EXPIRE', KEYS[2], ARGV[3]) " +
            "redis.call('EXPIRE', KEYS[3], ARGV[3])";
            
        stringRedisTemplate.execute(
            RedisScript.of(script, Void.class),
            List.of(countKey, likeHashKey, dislikeSetKey),
            fileId, categoryId.toString(), String.valueOf(unit.toSeconds(timeout))
        );
    }

    /**
     * 新的取消点赞操作（使用Hash+Set混合结构）
     * @param countKey 计数键 gif:like:fileId
     * @param likeHashKey 点赞Hash键 user:like:category:userId
     * @param dislikeSetKey 不喜欢Set键 user:dislike:userId  
     * @param fileId 文件ID
     * @param timeout 过期时间
     * @param unit 时间单位
     */
    public void dislikeOperationOptimized(String countKey, String likeHashKey, String dislikeSetKey, String fileId, long timeout, TimeUnit unit) {
        String script = 
            "if redis.call('HEXISTS', KEYS[2], ARGV[1]) == 1 then " +
            "    redis.call('HDEL', KEYS[2], ARGV[1]) " +
            "else " +
            "    redis.call('SADD', KEYS[3], ARGV[1]) " +
            "    redis.call('EXPIRE', KEYS[3], ARGV[2]) " +
            "end " +
            "redis.call('DECR', KEYS[1]) " +
            "redis.call('EXPIRE', KEYS[1], ARGV[2]) " +
            "redis.call('EXPIRE', KEYS[2], ARGV[2])";
            
        stringRedisTemplate.execute(
            RedisScript.of(script, Void.class),
            List.of(countKey, likeHashKey, dislikeSetKey),
            fileId, String.valueOf(unit.toSeconds(timeout))
        );
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
     * 获取set集合中的所有元素（String类型）
     * @param key 键
     * @return String类型的元素集合
     */
    public Set<String> getStringSet(String key) {
        Set<String> stringSet = stringRedisTemplate.opsForSet().members(key);
        return stringSet != null ? stringSet : Set.of();
    }

    /**
     * 安全获取set集合，支持oldKey备份机制
     * @param key 键
     * @param clearAfterGet 获取后是否清空
     * @return String类型的元素集合
     */
    public Set<String> getStringSetSafely(String key, boolean clearAfterGet) {
        try {
            // 先创建备份，再获取并清空（确保数据安全）
            if (clearAfterGet && hasKey(key)) {
                String oldKey = key + ":old";
                copy(key, oldKey);
            }
            
            Set<String> result = getStringSet(key);
            if (clearAfterGet) delete(key);
            
            return result;
        } catch (Exception e) {
            log.error("安全获取Set失败: key={}, clear={}, 错误: {}", key, clearAfterGet, e.getMessage(), e);
            return Set.of();
        }
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
     * 获取所有有like或dislike数据的用户ID（新数据结构）
     * @param likeCategoryKeyPrefix 用户喜欢分类键前缀，如"user:like:category:"
     * @param dislikeKeyPrefix 用户不喜欢键前缀，如"user:dislike:"
     * @return 用户ID集合
     */
    public Set<String> getUserIdsWithLikeDataOptimized(String likeCategoryKeyPrefix, String dislikeKeyPrefix) {
        Set<String> likeKeys = getKeysWithPattern(likeCategoryKeyPrefix + "*");
        Set<String> dislikeKeys = getKeysWithPattern(dislikeKeyPrefix + "*");

        Set<String> userIds = new HashSet<>();
        int likePrefixLength = likeCategoryKeyPrefix.length();
        int dislikePrefixLength = dislikeKeyPrefix.length();

        // 从like category keys中提取用户ID
        for (String key : likeKeys) {
            if (key.length() > likePrefixLength) {
                String userId = key.substring(likePrefixLength);
                userIds.add(userId);
            }
        }

        // 从dislike keys中提取用户ID
        for (String key : dislikeKeys) {
            if (key.length() > dislikePrefixLength) {
                String userId = key.substring(dislikePrefixLength);
                userIds.add(userId);
            }
        }

        return userIds;
    }

    /**
     * 一次Redis请求完成：扫描匹配模式的key + 过滤非零值 + 获取值 + 重置为0
     * @param pattern 键的模式，如"gif:like:*"
     * @return 包含重置前非零值的键值对Map
     */
    public Map<String, Long> scanAndResetNonZeroCounters(String pattern) {
        // 使用SCAN + 过滤 + 获取 + 重置的Lua脚本
        String scriptText = 
            "local result = {}; " +
            "local cursor = '0'; " +
            "repeat " +
            "    local scanResult = redis.call('SCAN', cursor, 'MATCH', ARGV[1]); " +
            "    cursor = scanResult[1]; " +
            "    local keys = scanResult[2]; " +
            "    for i = 1, #keys do " +
            "        local value = redis.call('GET', keys[i]); " +
            "        if value and tonumber(value) ~= 0 then " +
            "            result[keys[i]] = tonumber(value); " +
            "            local ttl = redis.call('TTL', keys[i]); " + // 获取原来过期时间
            "            if ttl ~= -1 then " + // 不为-1，则设置过期时间为原来过期时间ttl
            "                redis.call('SET', keys[i], 0, 'EX', ttl); " +
            "            else " + // 如果为-1，则不设置过期时间
            "                redis.call('SET', keys[i], 0); " +
            "            end " +
            "        end " +
            "    end " +
            "until cursor == '0'; " +
            "return cjson.encode(result);";
        
        RedisScript<String> script = RedisScript.of(scriptText, String.class);
        
        try {
            // 使用StringRedisTemplate执行脚本，传入pattern参数
            String result = stringRedisTemplate.execute(script, List.of(), pattern);
            
            if (result.trim().isEmpty()) {
                return new HashMap<>();
            }
            
            // 解析JSON结果
            return objectMapper.readValue(result, new TypeReference<>() {
            });
        } catch (Exception e) {
            log.error("扫描并重置非零计数器失败: pattern={}, 错误: {}", pattern, e.getMessage(), e);
            return new HashMap<>();
        }
    }

    /**
     * 获取有序集合指定范围的元素
     * @param key 键
     * @param min 最小值
     * @param max 最大值
     * @param offset 偏移量
     * @param count 数量限制
     * @return 元素列表
     */
    public Set<String> zRangeByLex(String key, String min, String max, int offset, int count) {
        return stringRedisTemplate.opsForZSet().rangeByLex(
                key,
                Range.from(Range.Bound.inclusive(min)).to(Range.Bound.exclusive(max)),
                Limit.limit().offset(offset).count(count)
        );
    }

    /**
     * 添加元素到有序集合
     * @param key 键
     * @param member 元素
     * @param score 分数
     */
    public void zAdd(String key, String member, double score) {
        stringRedisTemplate.opsForZSet().add(key, member, score);
    }

    /**
     * 添加元素到有序集合 addIfAbsent
     * @param key 键
     * @param member 元素
     * @param score 分数
     */
    public void zAddNX(String key, String member, double score) {
        stringRedisTemplate.opsForZSet().addIfAbsent(key, member, score);
    }

    /**
     * 获取有序集合指定范围的元素
     * @param key 键
     * @param start 开始索引
     * @param end 结束索引
     * @return 元素列表
     */
    public Set<String> zRange(String key, long start, long end) {
        return stringRedisTemplate.opsForZSet().range(key, start, end);
    }

    /**
     * 替换热门标签
     *
     * @param args 参数
     * @param key hotKey
     */
    public void replaceHotTags(String[] args, String key) {
        String script =
                "local key = KEYS[1]; " +
                "redis.call('DEL', key); " +
                "for i = 1, #ARGV, 2 do " +
                "local score = tonumber(ARGV[i]); " +
                "local member = ARGV[i + 1]; " +
                "redis.call('ZADD', key, score, member); " +
                "end;";

        stringRedisTemplate.execute(
                RedisScript.of(script, Void.class),
                List.of(key),
                (Object[]) args
        );
    }
}