package com.mawai.ghgif.constant;

import lombok.Getter;

/**
 * 标签GIF排序类型
 */
@Getter
public enum TagGifSortType {
    /**
     * 按时间排序（最新在前）
     */
    TIME("time"),
    
    /**
     * 按热度排序（like_count）
     */
    HOT("hot"),
    
    /**
     * 随机排序
     */
    RANDOM("random");
    
    private final String code;
    
    TagGifSortType(String code) {
        this.code = code;
    }
    
    /**
     * 根据code获取排序类型
     */
    public static TagGifSortType fromCode(String code) {
        if (code == null || code.isEmpty()) {
            return TIME; // 默认时间排序
        }
        for (TagGifSortType type : values()) {
            if (type.code.equalsIgnoreCase(code)) {
                return type;
            }
        }
        return TIME;
    }
}

