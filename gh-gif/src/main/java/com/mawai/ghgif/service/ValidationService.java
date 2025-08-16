package com.mawai.ghgif.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;

/**
 * 文件验证服务类
 * 处理文件格式、大小等验证功能
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ValidationService {
    // 允许的文件类型
    private static final String ALLOWED_CONTENT_TYPES = "image/gif";

    // 文件大小限制（10MB）
    private static final long MAX_FILE_SIZE = 10 * 1024 * 1024;

    /**
     * 验证单个文件
     * @param file 文件
     * @return 验证结果消息，null表示验证通过
     */
    public String validateFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            return "文件不能为空";
        }

        // 检查文件大小
        if (file.getSize() > MAX_FILE_SIZE) {
            return "文件大小不能超过10MB";
        }

        // 检查文件类型
        String contentType = file.getContentType();
        if (!ALLOWED_CONTENT_TYPES.equalsIgnoreCase(contentType)) {
            return "只允许上传GIF格式的图片";
        }

        // 检查文件扩展名
        String originalFilename = file.getOriginalFilename();
        if (originalFilename == null || !originalFilename.toLowerCase().endsWith(".gif")) {
            return "文件扩展名必须为.gif";
        }

        // 检查文件内容魔数（GIF文件头）
        try {
            byte[] header = new byte[6];
            int bytesRead = file.getInputStream().read(header);
            if (bytesRead < 6) {
                return "文件内容不完整";
            }
            
            // GIF文件魔数检查：GIF87a 或 GIF89a
            String headerStr = new String(header);
            if (!headerStr.equals("GIF87a") && !headerStr.equals("GIF89a")) {
                return "文件不是有效的GIF格式";
            }
        } catch (IOException e) {
            log.error("读取文件头失败", e);
            return "文件读取失败";
        }

        return null; // 验证通过
    }

    /**
     * 验证文件列表
     * @param files 文件列表
     * @return 验证结果消息，null表示验证通过
     */
    public String validateFiles(List<MultipartFile> files) {
        // 检查批量上传数量限制
        if (files.size() > 5) {
            return "单次最多只能上传5个文件";
        }

        // 逐个验证文件
        for (int i = 0; i < files.size(); i++) {
            String fileValidation = validateFile(files.get(i));
            if (fileValidation != null) {
                return "第" + (i + 1) + "个文件验证失败：" + fileValidation;
            }
        }

        return null; // 验证通过
    }



}

