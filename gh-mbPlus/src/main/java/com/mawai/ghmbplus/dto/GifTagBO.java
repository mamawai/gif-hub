package com.mawai.ghmbplus.dto;

import lombok.Data;

/**
 * GIF标签业务对象
 * 用于接收数据库查询结果
 */
@Data
public class GifTagBO {
    private Long tagId;      // 标签ID
    private String tagName;  // 标签名称
}

