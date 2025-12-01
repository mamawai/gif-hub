package com.mawai.ghmbplus.dto;

import lombok.Data;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;

/**
 * 评论详情缓存对象（用于 Redis Hash 统一存储）
 * 
 * <p>整合了根评论和子评论的所有字段，使用 Redis Hash 结构存储</p>
 * <p>根评论：parentId、rootCommentId、parentUserId 为 null</p>
 * <p>子评论：所有字段都有值</p>
 * 
 * <p>Redis 结构：</p>
 * <pre>
 * comment:detail:123 → Hash
 *   - id: "123"
 *   - gifId: "456"
 *   - userId: "789"
 *   - content: "很棒的评论"
 *   - likeCount: "10"
 *   - createdAt: "1730275200"  (Unix timestamp)
 *   - avatar: "https://..."
 *   - parentId: ""  (根评论为空字符串)
 *   - parentUserId: ""
 *   - rootCommentId: ""
 * </pre>
 * 
 * @author mawai
 * @since 2025-10-30
 */
@Data
public class CommentDetailCacheBO {
    
    // ========== 通用字段（根评论和子评论都有） ==========
    private Long id;
    private Long gifId;
    private Long userId;
    private String content;
    private Long likeCount;  // ← 重点：定时任务会使用 HINCRBY 直接更新此字段
    private LocalDateTime createdAt;
    private String avatar;
    
    // ========== 子评论特有字段（根评论时为 null） ==========
    private Long parentId;
    private Long parentUserId;
    private Long rootCommentId;
    
    /**
     * 从 RootCommentBO 转换为缓存对象
     */
    public static CommentDetailCacheBO fromRoot(RootCommentBO root) {
        // 子评论字段保持 null
        return baseBoCacheBuild(root.getId(), root.getGifId(), root.getUserId(), root.getContent(),
                root.getLikeCount(), root.getCreatedAt(), root.getAvatar());
    }

    /**
     * 从 ChildCommentBO 转换为缓存对象
     */
    public static CommentDetailCacheBO fromChild(ChildCommentBO child) {
        CommentDetailCacheBO cacheBO = baseBoCacheBuild(child.getId(), child.getGifId(), child.getUserId(), child.getContent(),
                child.getLikeCount(), child.getCreatedAt(), child.getAvatar());
        // 子评论特有字段
        cacheBO.setParentId(child.getParentId());
        cacheBO.setParentUserId(child.getParentUserId());
        cacheBO.setRootCommentId(child.getRootCommentId());
        return cacheBO;
    }

    /**
     * 构建 CommentDetailCacheBO 的基本字段（根评论和子评论都通用）
     */
    private static CommentDetailCacheBO baseBoCacheBuild(Long id, Long gifId, Long userId, String content, Long likeCount, LocalDateTime createdAt, String avatar) {
        CommentDetailCacheBO cache = new CommentDetailCacheBO();
        cache.setId(id);
        cache.setGifId(gifId);
        cache.setUserId(userId);
        cache.setContent(content);
        cache.setLikeCount(likeCount);
        cache.setCreatedAt(createdAt);
        cache.setAvatar(avatar);
        return cache;
    }
    
    /**
     * 判断是否为根评论
     */
    public boolean isRootComment() {
        return rootCommentId == null;
    }
    
    /**
     * 转换为 Redis Hash Map（用于存储）
     * 
     * @return Redis Hash 的 field-value 映射
     */
    public Map<String, String> toHashMap() {
        Map<String, String> hash = new HashMap<>();
        
        // 必填字段
        hash.put("id", String.valueOf(id));
        hash.put("gifId", String.valueOf(gifId));
        hash.put("userId", String.valueOf(userId));
        hash.put("content", content != null ? content : "");
        hash.put("likeCount", String.valueOf(likeCount != null ? likeCount : 0L));
        hash.put("createdAt", String.valueOf(createdAt.atZone(ZoneOffset.UTC).toEpochSecond()));
        hash.put("avatar", avatar != null ? avatar : "");
        
        // 可选字段（子评论特有）
        hash.put("parentId", parentId != null ? String.valueOf(parentId) : "");
        hash.put("parentUserId", parentUserId != null ? String.valueOf(parentUserId) : "");
        hash.put("rootCommentId", rootCommentId != null ? String.valueOf(rootCommentId) : "");
        
        return hash;
    }
    
    /**
     * 从 Redis Hash Map 解析为对象
     * 
     * @param hash Redis Hash 的 field-value 映射
     * @return CommentDetailCacheBO 对象，如果 hash 为空则返回 null
     */
    public static CommentDetailCacheBO fromHashMap(Map<String, String> hash) {
        if (hash == null || hash.isEmpty()) {
            return null;
        }
        
        CommentDetailCacheBO bo = new CommentDetailCacheBO();
        
        // 必填字段
        bo.setId(parseLong(hash.get("id")));
        bo.setGifId(parseLong(hash.get("gifId")));
        bo.setUserId(parseLong(hash.get("userId")));
        bo.setContent(hash.get("content"));
        bo.setLikeCount(parseLong(hash.get("likeCount")));
        
        // 时间戳转换（毫秒）
        Long timestamp = parseLong(hash.get("createdAt"));
        if (timestamp != null) {
            bo.setCreatedAt(LocalDateTime.ofInstant(Instant.ofEpochSecond(timestamp), ZoneOffset.UTC));
        }
        
        bo.setAvatar(hash.get("avatar"));
        
        // 可选字段（子评论特有）
        bo.setParentId(parseOptionalLong(hash.get("parentId")));
        bo.setParentUserId(parseOptionalLong(hash.get("parentUserId")));
        bo.setRootCommentId(parseOptionalLong(hash.get("rootCommentId")));
        
        return bo;
    }
    
    /**
     * 解析 Long（必填字段）
     */
    private static Long parseLong(String value) {
        if (value == null || value.isEmpty()) {
            return null;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }
    
    /**
     * 解析可选 Long（空字符串视为 null）
     */
    private static Long parseOptionalLong(String value) {
        if (value == null || value.isEmpty()) {
            return null;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }
    
    /**
     * 解析可选 String（空字符串视为 null）
     */
    private static String parseOptionalString(String value) {
        if (value == null || value.isEmpty()) {
            return null;
        }
        return value;
    }
}

