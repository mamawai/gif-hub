package com.mawai.ghgif.vo;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;

/**
 * Giphy API 响应封装
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class GiphyResponseVO {
    /**
     * GIF 数据列表（search/trending/byIds）
     */
    private List<GiphyGifVO> data;

    /**
     * 分页信息（仅 search/trending/byIds 有）
     */
    private Pagination pagination;

    /**
     * 响应元信息
     */
    private Meta meta;

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Pagination {
        @JsonProperty("total_count")
        private Integer totalCount;

        private Integer count;

        private Integer offset;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Meta {
        /**
         * HTTP 状态码
         */
        private Integer status;

        /**
         * 响应消息
         */
        private String msg;
    }
}