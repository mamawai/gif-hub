package com.mawai.ghgif.vo;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class GifVO {
    private Long id; // GIF ID
    private String giphyId; // Giphy ID
    private String height; // 高度
    private String width; // 宽度
    private String title; // 标题
    private String description; // 描述
    private String source; // 来源
    private String giphyUsername; // Giphy 用户名
    private Long likeCount; // 点赞次数
    private Long downloadCount; // 下载次数
    private Long viewCount; // 查看次数
    private String userId; // 上传用户ID，0表示来自Giphy
    private LocalDateTime createdAt; // 创建时间
}