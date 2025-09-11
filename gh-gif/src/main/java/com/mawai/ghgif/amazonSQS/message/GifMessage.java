package com.mawai.ghgif.amazonSQS.message;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * GIF处理消息
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GifMessage implements Serializable {
    
    @Serial
    private static final long serialVersionUID = 1L;
    // GIF相关信息
    private Long userId;
    private String title;
    private String fileUrl;
    private String description;
    private List<String> tags;
    private Integer fileSize;
}
