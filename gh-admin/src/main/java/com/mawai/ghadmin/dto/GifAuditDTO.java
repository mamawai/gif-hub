package com.mawai.ghadmin.dto;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class GifAuditDTO {
    private Long id;          // 审核ID
    private String fileUrl;   // 文件URL
    private String title;
    private String description;
    private String tags;
    private Long userId;
    private LocalDateTime createdAt;
    private LocalDateTime auditAt;
} 