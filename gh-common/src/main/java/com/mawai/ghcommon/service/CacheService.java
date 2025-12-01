package com.mawai.ghcommon.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.Limit;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.*;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
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

    private static final int COUNTER_SCAN_BATCH_SIZE = 500;
    private static final byte[] ZERO_BYTES = "0".getBytes(StandardCharsets.UTF_8);

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
     * 删除键（异步非阻塞）
     * @param key 键
     */
    public boolean delete(String key) {
        return Boolean.TRUE.equals(redisTemplate.unlink(key));
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
            if (clearAfterGet) redisTemplate.unlink(categoryKey);
            
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
     * 评论点赞操作（支持取消点赞）- 使用Set+Set混合结构
     * @param countKey 计数键 comment:like:commentId
     * @param likeSetKey 点赞Set键 user:comment:like:userId
     * @param dislikeSetKey 取消点赞Set键 user:comment:dislike:userId
     * @param commentId 评论ID
     * @param timeout 过期时间
     * @param unit 时间单位
     */
    public void commentLikeOperation(String countKey, String likeSetKey, String dislikeSetKey, String commentId, long timeout, TimeUnit unit) {
        String script = 
            // 先检查dislike set，如果有则删除
            "if redis.call('SISMEMBER', KEYS[3], ARGV[1]) == 1 then " +
            "    redis.call('SREM', KEYS[3], ARGV[1]) " +
            "end " +
            // 尝试添加到like set，返回1表示新增成功，0表示已存在
            "local added = redis.call('SADD', KEYS[2], ARGV[1]) " +
            "if added == 1 then " +
            "    redis.call('INCR', KEYS[1]) " +  // 只有新增成功才+1，保证幂等性
            "end " +
            "redis.call('EXPIRE', KEYS[1], ARGV[2]) " +
            "redis.call('EXPIRE', KEYS[2], ARGV[2]) " +
            "redis.call('EXPIRE', KEYS[3], ARGV[2]) " +
            "return added";  // 返回1表示点赞成功，0表示已点赞
            
        stringRedisTemplate.execute(
            RedisScript.of(script, Long.class),
            List.of(countKey, likeSetKey, dislikeSetKey),
            commentId, String.valueOf(unit.toSeconds(timeout))
        );
    }
    
    /**
     * 评论取消点赞操作（使用Set+Set混合结构）
     * @param countKey 计数键 comment:like:commentId
     * @param likeSetKey 点赞Set键 user:comment:like:userId
     * @param dislikeSetKey 取消点赞Set键 user:comment:dislike:userId
     * @param commentId 评论ID
     * @param timeout 过期时间
     * @param unit 时间单位
     */
    public void commentDislikeOperation(String countKey, String likeSetKey, String dislikeSetKey, String commentId, long timeout, TimeUnit unit) {
        String script = 
            // 检查like set，如果有则删除
            "if redis.call('SISMEMBER', KEYS[2], ARGV[1]) == 1 then " +
            "    redis.call('SREM', KEYS[2], ARGV[1]) " +
            "else " +
            // 如果like set中没有，说明是数据库中的老数据，标记到dislike set
            "    redis.call('SADD', KEYS[3], ARGV[1]) " +
            "    redis.call('EXPIRE', KEYS[3], ARGV[2]) " +
            "end " +
            "redis.call('DECR', KEYS[1]) " +  // 无论如何都-1
            "redis.call('EXPIRE', KEYS[1], ARGV[2]) " +
            "redis.call('EXPIRE', KEYS[2], ARGV[2])";
            
        stringRedisTemplate.execute(
            RedisScript.of(script, Void.class),
            List.of(countKey, likeSetKey, dislikeSetKey),
            commentId, String.valueOf(unit.toSeconds(timeout))
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
            if (clearAfterGet) delete(key);  // 调用上面的 delete() 方法，已改为 unlink
            
            return result;
        } catch (Exception e) {
            log.error("安全获取Set失败: key={}, clear={}, 错误: {}", key, clearAfterGet, e.getMessage(), e);
            return Set.of();
        }
    }

    /**
     * 使用 KEYS 命令获取匹配的 key（仅用于开发/测试环境）
     * 生产环境请使用 scanKeys 方法
     * 
     * @param pattern 匹配模式
     * @return key 集合
     * @deprecated 生产环境禁用，请使用 scanKeys(String pattern, int count)
     */
    @Deprecated
    public Set<String> getKeysWithPattern(String pattern) {
        return redisTemplate.keys(pattern);
    }
    
    /**
     * 使用 SCAN 命令获取匹配的 key（推荐）
     * <ul>
     *   <li>渐进式遍历，不阻塞 Redis</li>
     *   <li>适合大数据量场景</li>
     *   <li>可控制每次迭代的数量</li>
     * </ul>
     * 
     * @param pattern 匹配模式，如 "user:comment:like:*"
     * @param count 每次迭代返回的建议数量（实际可能更多或更少）
     *              推荐值：500-1000（根据数据量调整）
     * @return key 集合
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
     * 获取所有有like或dislike数据的用户ID（使用 SCAN 优化）
     * 
     * @param likeCategoryKeyPrefix 用户喜欢分类键前缀，如"user:like:category:"
     * @param dislikeKeyPrefix 用户不喜欢键前缀，如"user:dislike:"
     * @return 用户ID集合
     */
    public Set<String> getUserIdsWithLikeDataOptimized(String likeCategoryKeyPrefix, String dislikeKeyPrefix, int count) {
        // 使用 SCAN 替代 KEYS
        Set<String> likeKeys = scanKeys(likeCategoryKeyPrefix + "*", count);
        Set<String> dislikeKeys = scanKeys(dislikeKeyPrefix + "*", count);

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
     * 扫描并重置非零计数器（Pipeline 优化）
     *
     * <p>功能：使用 SCAN 渐进式遍历匹配的键，通过 GETSET 原子操作读取旧值并重置为 0，自动恢复 TTL
     * <p>场景：定时同步 Redis 计数器到数据库（如点赞数、浏览数）
     *
     * @param pattern 键的匹配模式，如 "gif:like:*"
     * @return 重置前非零计数器的 Map（key -> 旧值），值为 0 的键不返回
     */
    public Map<String, Long> scanAndResetNonZeroCounters(String pattern) {
        // 存储所有非零计数器的结果
        Map<String, Long> counterMap = new HashMap<>();
        
        // 配置 SCAN 选项：匹配模式和每次迭代建议返回数量
        ScanOptions options = ScanOptions.scanOptions()
                .match(pattern)
                .count(COUNTER_SCAN_BATCH_SIZE)  // 每次 SCAN 建议返回 500 个键
                .build();

        try {
            // 使用 RedisCallback 获取底层 RedisConnection，支持 Pipeline 操作
            stringRedisTemplate.execute((RedisCallback<Void>) connection -> {
                processCountersWithPipeline(connection, options, counterMap);
                return null;
            });
        } catch (Exception e) {
            log.error("扫描并重置非零计数器失败: pattern={}, 错误: {}", pattern, e.getMessage(), e);
        }

        return counterMap;
    }

    /**
     * 使用 Pipeline 批量处理计数器
     *
     * <p>处理流程：
     * <ol>
     *   <li>SCAN 遍历键，每 500 个键收集为一批</li>
     *   <li>对每批调用 processBatch 进行 Pipeline 处理</li>
     * </ol>
     *
     * @param connection Redis 连接
     * @param options SCAN 配置
     * @param counterMap 结果收集 Map
     */
    private void processCountersWithPipeline(RedisConnection connection,
                                             ScanOptions options,
                                             Map<String, Long> counterMap) {
        try (Cursor<byte[]> cursor = connection.keyCommands().scan(options)) {
            List<byte[]> batchKeys = new ArrayList<>(COUNTER_SCAN_BATCH_SIZE);
            
            while (cursor.hasNext()) {
                batchKeys.add(cursor.next());
                
                if (batchKeys.size() >= COUNTER_SCAN_BATCH_SIZE) {
                    processBatch(connection, batchKeys, counterMap);
                    batchKeys.clear();
                }
            }
            
            if (!batchKeys.isEmpty()) {
                processBatch(connection, batchKeys, counterMap);
            }
        }
    }

    /**
     * 处理单批计数器
     *
     * <p>处理流程：
     * <ol>
     *   <li>Pipeline 1：批量执行 PTTL + GETSET，获取 TTL 和旧值并重置为 0</li>
     *   <li>解析结果：过滤非零值，记录到 counterMap</li>
     *   <li>Pipeline 2：批量恢复 TTL（GETSET 会清除过期时间）</li>
     * </ol>
     *
     * @param connection Redis 连接
     * @param batchKeys 批次键列表
     * @param counterMap 结果收集 Map
     */
    private void processBatch(RedisConnection connection,
                             List<byte[]> batchKeys,
                             Map<String, Long> counterMap) {
        if (batchKeys.isEmpty()) {
            return;
        }

        // Pipeline 1: PTTL + GETSET
        connection.openPipeline();
        for (byte[] key : batchKeys) {
            connection.keyCommands().pTtl(key);
            connection.stringCommands().getSet(key, ZERO_BYTES);
        }
        List<Object> replies = connection.closePipeline();

        // 解析结果 & 直接恢复 TTL
        connection.openPipeline();
        for (int i = 0; i < batchKeys.size(); i++) {
            Object ttlObj = replies.get(i * 2);
            Object valueObj = replies.get(i * 2 + 1);

            byte[] valueBytes = valueObj instanceof byte[] vb ? vb : null;
            if (valueBytes == null) continue;

            long value = Long.parseLong(new String(valueBytes, StandardCharsets.UTF_8));
            
            if (value != 0) {
                String keyStr = new String(batchKeys.get(i), StandardCharsets.UTF_8);
                counterMap.put(keyStr, value);
            }

            if (ttlObj instanceof Number number && number.longValue() > 0) {
                connection.keyCommands().pExpire(batchKeys.get(i), number.longValue());
            }
        }
        connection.closePipeline();
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
     * 获取有序集合指定范围的元素（带分数）
     * @param key 键
     * @param start 开始索引
     * @param end 结束索引
     * @return 元素列表
     */
    public Set<ZSetOperations.TypedTuple<String>> zRangeWithScores(String key, long start, long end) {
        return stringRedisTemplate.opsForZSet().rangeWithScores(key, start, end);
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
                "redis.call('UNLINK', key); " +
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

    /**
     * 存储List到Redis（序列化为JSON）
     * @param key 键
     * @param list 列表数据
     * @param timeout 过期时间
     * @param unit 时间单位
     * @param <T> 列表元素类型
     */
    public <T> void setList(String key, List<T> list, long timeout, TimeUnit unit) {
        try {
            String json = objectMapper.writeValueAsString(list);
            stringRedisTemplate.opsForValue().set(key, json, timeout, unit);
        } catch (Exception e) {
            log.error("存储List到Redis失败: key={}, error={}", key, e.getMessage(), e);
            throw new RuntimeException("存储List到Redis失败: " + e.getMessage(), e);
        }
    }

    /**
     * 从Redis获取List（反序列化JSON）
     * @param key 键
     * @param clazz 列表元素类型
     * @param <T> 列表元素类型
     * @return List对象，不存在返回null
     */
    public <T> List<T> getList(String key, Class<T> clazz) {
        try {
            String json = stringRedisTemplate.opsForValue().get(key);
            if (json == null || json.isEmpty()) {
                return null;
            }
            return objectMapper.readValue(json, 
                objectMapper.getTypeFactory().constructCollectionType(List.class, clazz));
        } catch (Exception e) {
            log.error("从Redis获取List失败: key={}, error={}", key, e.getMessage(), e);
            return null;
        }
    }

    /**
     * 删除匹配pattern的所有key（使用SCAN避免阻塞，UNLINK异步删除）
     * @param pattern 匹配模式
     * @return 删除的key数量
     */
    public long deletePattern(String pattern) {
        try {
            Set<String> keys = scanKeys(pattern, 500);
            if (keys.isEmpty()) {
                return 0;
            }
            Long deleted = stringRedisTemplate.unlink(keys);
            log.info("删除匹配pattern的key: pattern={}, count={}", pattern, deleted);
            return deleted != null ? deleted : 0;
        } catch (Exception e) {
            log.error("删除匹配pattern的key失败: pattern={}, error={}", pattern, e.getMessage(), e);
            return 0;
        }
    }

    // ==================== ZSet 操作方法 ====================

    /**
     * 获取ZSet指定范围的元素（倒序：高分在前）
     * @param key ZSet的key
     * @param start 开始索引（0-based）
     * @param end 结束索引（包含）
     * @return 元素集合（LinkedHashSet保持顺序）
     */
    public Set<String> zsetReverseRange(String key, long start, long end) {
        try {
            return stringRedisTemplate.opsForZSet().reverseRange(key, start, end);
        } catch (Exception e) {
            log.error("ZSet倒序查询失败: key={}, start={}, end={}, error={}", 
                     key, start, end, e.getMessage(), e);
            return new java.util.LinkedHashSet<>();
        }
    }

    /**
     * 添加元素到ZSet
     * @param key ZSet的key
     * @param value 元素值
     * @param score 分数
     * @return 是否添加成功（true=新增，false=更新）
     */
    public Boolean zsetAdd(String key, String value, double score) {
        try {
            return stringRedisTemplate.opsForZSet().add(key, value, score);
        } catch (Exception e) {
            log.error("ZSet添加元素失败: key={}, value={}, score={}, error={}", 
                     key, value, score, e.getMessage(), e);
            return false;
        }
    }

    /**
     * 从ZSet删除元素
     * @param key ZSet的key
     * @param values 要删除的元素（可变参数）
     * @return 删除的元素数量
     */
    public Long zsetRemove(String key, String... values) {
        try {
            return stringRedisTemplate.opsForZSet().remove(key, (Object[]) values);
        } catch (Exception e) {
            log.error("ZSet删除元素失败: key={}, error={}", key, e.getMessage(), e);
            return 0L;
        }
    }

    /**
     * 获取ZSet的大小
     * @param key ZSet的key
     * @return 元素数量
     */
    public Long zSetSize(String key) {
        try {
            return stringRedisTemplate.opsForZSet().zCard(key);
        } catch (Exception e) {
            log.error("ZSet获取大小失败: key={}, error={}", key, e.getMessage(), e);
            return 0L;
        }
    }

    /**
     * 删除ZSet中指定排名范围的元素（按score从低到高）
     * @param key ZSet的key
     * @param start 开始排名
     * @param end 结束排名
     * @return 删除的元素数量
     */
    public Long zsetRemoveRange(String key, long start, long end) {
        try {
            return stringRedisTemplate.opsForZSet().removeRange(key, start, end);
        } catch (Exception e) {
            log.error("ZSet删除范围失败: key={}, start={}, end={}, error={}", 
                     key, start, end, e.getMessage(), e);
            return 0L;
        }
    }

    /**
     * 批量添加元素到ZSet
     * @param key ZSet的key
     * @param scoreMembers Map<元素, 分数>
     * @return 新增的元素数量
     */
    public Long zsetAddBatch(String key, Map<String, Double> scoreMembers) {
        try {
            Set<ZSetOperations.TypedTuple<String>> tuples = new HashSet<>();
            scoreMembers.forEach((member, score) -> {
                tuples.add(new DefaultTypedTuple<>(member, score));
            });
            return stringRedisTemplate.opsForZSet().add(key, tuples);
        } catch (Exception e) {
            log.error("ZSet批量添加失败: key={}, size={}, error={}", 
                     key, scoreMembers.size(), e.getMessage(), e);
            return 0L;
        }
    }
    
    // ==================== Hash 操作方法 ====================
    
    /**
     * Hash 批量设置字段（使用 ziplist 优化内存）
     * 
     * @param key Hash 的 key
     * @param hash field-value 映射
     * @param timeout 过期时间
     * @param unit 时间单位
     */
    public void hashSetAll(String key, Map<String, String> hash, long timeout, TimeUnit unit) {
        try {
            stringRedisTemplate.opsForHash().putAll(key, hash);
            stringRedisTemplate.expire(key, timeout, unit);
        } catch (Exception e) {
            log.error("Hash批量设置失败: key={}, size={}, error={}", 
                     key, hash.size(), e.getMessage(), e);
            throw new RuntimeException("Hash批量设置失败: " + e.getMessage(), e);
        }
    }
    
    /**
     * Hash 获取所有字段
     * 
     * @param key Hash 的 key
     * @return field-value 映射，如果 key 不存在则返回空 Map
     */
    public Map<String, String> hashGetAll(String key) {
        try {
            Map<Object, Object> rawMap = stringRedisTemplate.opsForHash().entries(key);
            
            // 转换类型（Redis 返回的是 Object 类型）
            Map<String, String> result = new HashMap<>();
            for (Map.Entry<Object, Object> entry : rawMap.entrySet()) {
                result.put(entry.getKey().toString(), entry.getValue().toString());
            }
            
            return result;
        } catch (Exception e) {
            log.error("Hash获取所有字段失败: key={}, error={}", key, e.getMessage(), e);
            return new HashMap<>();
        }
    }
    
    /**
     * Hash 原子递增字段值（适用于计数器场景）
     * 
     * <p>使用 HINCRBY 命令，原子性操作，无需先读再写</p>
     * 
     * @param key Hash 的 key
     * @param field 字段名
     * @param delta 增量（可以为负数）
     * @return 递增后的值
     */
    public Long hashIncrement(String key, String field, long delta) {
        try {
            return stringRedisTemplate.opsForHash().increment(key, field, delta);
        } catch (Exception e) {
            log.error("Hash递增字段失败: key={}, field={}, delta={}, error={}", 
                     key, field, delta, e.getMessage(), e);
            throw new RuntimeException("Hash递增字段失败: " + e.getMessage(), e);
        }
    }
    
    /**
     * Hash 原子递增字段值并设置过期时间（Lua 脚本保证原子性）
     *
     * @param key     Hash 的 key
     * @param field   字段名
     * @param delta   增量
     * @param timeout 过期时间
     * @param unit    时间单位
     */
    public void hashIncrementWithExpire(String key, String field, long delta, long timeout, TimeUnit unit) {
        String script = 
            "redis.call('HINCRBY', KEYS[1], ARGV[1], ARGV[2]); " +
            "redis.call('EXPIRE', KEYS[1], ARGV[3]);";
        
        try {
            stringRedisTemplate.execute(
                    RedisScript.of(script, Void.class),
                    List.of(key),
                    field,
                    String.valueOf(delta),
                    String.valueOf(unit.toSeconds(timeout))
            );
        } catch (Exception e) {
            log.error("Hash递增字段并设置过期时间失败: key={}, field={}, error={}", 
                     key, field, e.getMessage(), e);
            throw new RuntimeException("Hash递增字段失败: " + e.getMessage(), e);
        }
    }
    
    /**
     * Hash 批量删除（根据 key 列表，使用 UNLINK 异步删除）
     * 
     * @param keys Hash key 列表
     * @return 删除成功的数量
     */
    public int hashBatchDelete(List<String> keys) {
        if (keys == null || keys.isEmpty()) {
            return 0;
        }
        
        try {
            Long deleted = stringRedisTemplate.unlink(keys);
            return deleted.intValue();
        } catch (Exception e) {
            log.error("Hash批量删除失败: size={}, error={}", keys.size(), e.getMessage(), e);
            return 0;
        }
    }
    
    /**
     * Hash 判断字段是否存在
     * 
     * @param key Hash 的 key
     * @param field 字段名
     * @return 是否存在
     */
    public boolean hashHasField(String key, String field) {
        try {
            return Boolean.TRUE.equals(stringRedisTemplate.opsForHash().hasKey(key, field));
        } catch (Exception e) {
            log.error("Hash判断字段存在失败: key={}, field={}, error={}",
                     key, field, e.getMessage(), e);
            return false;
        }
    }
    
    /**
     * Hash批量获取多个字段（支持多个key）
     * @param keyFieldsMap shardId -> ids in the shard 映射
     * @return key -> (field -> value) 映射
     */
    public Map<String, Map<String, String>> hashMultiGet(Map<String, List<String>> keyFieldsMap) {
        Map<String, Map<String, String>> result = new HashMap<>();
        try {
            for (Map.Entry<String, List<String>> entry : keyFieldsMap.entrySet()) {
                // shard key
                String key = entry.getKey();
                // ids in the shard
                List<String> fields = entry.getValue();
                
                List<Object> values = stringRedisTemplate.opsForHash().multiGet(key, new ArrayList<>(fields));
                Map<String, String> fieldValueMap = new HashMap<>();
                
                for (int i = 0; i < fields.size(); i++) {
                    Object value = values.get(i);
                    if (value != null) {
                        fieldValueMap.put(fields.get(i), value.toString());
                    }
                }

                // shardId -> (id, name)map类型
                result.put(key, fieldValueMap);
            }
        } catch (Exception e) {
            log.error("Hash批量获取多个字段失败: error={}", e.getMessage(), e);
        }
        return result;
    }
    
    /**
     * Hash设置单个字段（不设置过期时间）
     * @param key Hash的key
     * @param field 字段名
     * @param value 字段值
     */
    public void hashSet(String key, String field, String value) {
        try {
            stringRedisTemplate.opsForHash().put(key, field, value);
        } catch (Exception e) {
            log.error("Hash设置字段失败: key={}, field={}, error={}",
                     key, field, e.getMessage(), e);
            throw new RuntimeException("Hash设置字段失败: " + e.getMessage(), e);
        }
    }
    
    /**
     * Hash删除单个字段
     * @param key Hash的key
     * @param field 字段名
     * @return 是否删除成功
     */
    public boolean hashDelete(String key, String field) {
        try {
            return stringRedisTemplate.opsForHash().delete(key, field) > 0;
        } catch (Exception e) {
            log.error("Hash删除字段失败: key={}, field={}, error={}",
                     key, field, e.getMessage(), e);
            return false;
        }
    }
}
