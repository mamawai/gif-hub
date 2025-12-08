package com.mawai.ghgif.vo;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

/**
 * Giphy GIF 数据对象（简化版）
 * 只包含业务所需的核心字段
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class GiphyGifVO {
    /**
     * Giphy GIF ID
     */
    private String id;

    /**
     * Giphy 用户名
     */
    private String username;

    /**
     * 来源
     */
    private String source;

    /**
     * GIF 标题
     */
    private String title;

    /**
     * 原始图片高度
     */
    @JsonProperty("images")
    private Images images;

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Images {
        private Original original;
        
        @Data
        @JsonIgnoreProperties(ignoreUnknown = true)
        public static class Original {
            private String height;
            private String width;
        }
    }
}