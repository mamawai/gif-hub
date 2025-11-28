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
 * <p>根评论：parentId、rootCommentId、parentUserId、parentNickname 为 null</p>
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
 *   - nickname: "张三"
 *   - avatar: "https://..."
 *   - parentId: ""  (根评论为空字符串)
 *   - parentUserId: ""
 *   - rootCommentId: ""
 *   - parentNickname: ""
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
    private String nickname;
    private String avatar;
    
    // ========== 子评论特有字段（根评论时为 null） ==========
    private Long parentId;
    private Long parentUserId;
    private Long rootCommentId;
    private String parentNickname;
    
    /**
     * 从 RootCommentBO 转换为缓存对象
     */
    public static CommentDetailCacheBO fromRoot(RootCommentBO root) {
        CommentDetailCacheBO cache = new CommentDetailCacheBO();
        cache.setId(root.getId());
        cache.setGifId(root.getGifId());
        cache.setUserId(root.getUserId());
        cache.setContent(root.getContent());
        cache.setLikeCount(root.getLikeCount());
        cache.setCreatedAt(root.getCreatedAt());
        cache.setNickname(root.getNickname());
        cache.setAvatar(root.getAvatar());
        // 子评论字段保持 null
        return cache;
    }
    
    /**
     * 从 ChildCommentBO 转换为缓存对象
     */
    public static CommentDetailCacheBO fromChild(ChildCommentBO child) {
        CommentDetailCacheBO cache = new CommentDetailCacheBO();
        cache.setId(child.getId());
        cache.setGifId(child.getGifId());
        cache.setUserId(child.getUserId());
        cache.setContent(child.getContent());
        cache.setLikeCount(child.getLikeCount());
        cache.setCreatedAt(child.getCreatedAt());
        cache.setNickname(child.getNickname());
        cache.setAvatar(child.getAvatar());
        // 子评论特有字段
        cache.setParentId(child.getParentId());
        cache.setParentUserId(child.getParentUserId());
        cache.setRootCommentId(child.getRootCommentId());
        cache.setParentNickname(child.getParentNickname());
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
        hash.put("createdAt", String.valueOf(createdAt.atZone(ZoneOffset.UTC).toInstant().toEpochMilli()));
        hash.put("nickname", nickname != null ? nickname : "");
        hash.put("avatar", avatar != null ? avatar : "");
        
        // 可选字段（子评论特有）
        hash.put("parentId", parentId != null ? String.valueOf(parentId) : "");
        hash.put("parentUserId", parentUserId != null ? String.valueOf(parentUserId) : "");
        hash.put("rootCommentId", rootCommentId != null ? String.valueOf(rootCommentId) : "");
        hash.put("parentNickname", parentNickname != null ? parentNickname : "");
        
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
            bo.setCreatedAt(LocalDateTime.ofInstant(Instant.ofEpochMilli(timestamp), ZoneOffset.UTC));
        }
        
        bo.setNickname(hash.get("nickname"));
        bo.setAvatar(hash.get("avatar"));
        
        // 可选字段（子评论特有）
        bo.setParentId(parseOptionalLong(hash.get("parentId")));
        bo.setParentUserId(parseOptionalLong(hash.get("parentUserId")));
        bo.setRootCommentId(parseOptionalLong(hash.get("rootCommentId")));
        bo.setParentNickname(parseOptionalString(hash.get("parentNickname")));
        
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

