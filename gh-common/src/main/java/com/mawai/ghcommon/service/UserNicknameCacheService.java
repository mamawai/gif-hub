package com.mawai.ghcommon.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 用户昵称缓存服务
 * 负责管理分片的用户昵称缓存
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserNicknameCacheService {
    
    private static final String USER_NICKNAME_SHARD_KEY = "user:nickname:shard:";
    private static final int SHARD_COUNT = 8;
    
    private final CacheService cacheService;
    
    /**
     * 批量获取用户昵称
     * @param userIds 用户ID列表
     * @return userId -> nickname 映射
     */
    public Map<Long, String> batchGetNicknames(List<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return new HashMap<>();
        }
        
        // 按分片分组
        Map<String, List<String>> keyFieldsMap = new HashMap<>();
        for (Long userId : userIds) {
            int shardId = getShardId(userId);
            String key = USER_NICKNAME_SHARD_KEY + shardId;
            keyFieldsMap.computeIfAbsent(key, k -> new ArrayList<>()).add(String.valueOf(userId));
        }
        
        // 批量查询Redis
        Map<String, Map<String, String>> results = cacheService.hashMultiGet(keyFieldsMap);
        
        // 转换结果
        Map<Long, String> nicknameMap = new HashMap<>();
        for (Map<String, String> fieldValueMap : results.values()) {
            for (Map.Entry<String, String> entry : fieldValueMap.entrySet()) {
                nicknameMap.put(Long.parseLong(entry.getKey()), entry.getValue());
            }
        }
        
        return nicknameMap;
    }
    
    /**
     * 更新用户昵称
     * @param userId 用户ID
     * @param nickname 新昵称
     */
    public void updateNickname(Long userId, String nickname) {
        // 计算分片ID
        int shardId = getShardId(userId);
        String key = USER_NICKNAME_SHARD_KEY + shardId;
        cacheService.hashSet(key, String.valueOf(userId), nickname);
    }
    
    /**
     * 删除用户昵称（用户注销时调用）
     * @param userId 用户ID
     */
    public boolean deleteNickname(Long userId) {
        int shardId = getShardId(userId);
        String key = USER_NICKNAME_SHARD_KEY + shardId;
        return cacheService.hashDelete(key, String.valueOf(userId));
    }
    
    /**
     * 计算分片ID
     */
    private int getShardId(Long userId) {
        return (int) (userId % SHARD_COUNT);
    }
}