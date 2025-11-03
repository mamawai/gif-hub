package com.mawai.ghgif.vo;

import lombok.Data;

/**
 * GIF标签视图对象
 * 用于返回给前端
 */
@Data
public class GifTagVO {
    private Long id;    // 标签ID（用于跳转查询）
    private String name; // 标签名称（用于显示）
}

