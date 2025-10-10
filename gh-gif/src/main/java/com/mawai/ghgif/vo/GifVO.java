package com.mawai.ghgif.vo;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class GifVO {
    private Long id; // 主键ID
    private String fileUrl; // 文件URL
    private String title; // 标题
    private String description; // 描述
    private Long likeCount; // 点赞次数
    private Long downloadCount; // 下载次数
    private Long viewCount; // 查看次数
    private String userId; // 上传用户ID
    private LocalDateTime createdAt; // 创建时间
}