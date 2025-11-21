package com.mawai.ghgif.gifphy.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mawai.ghgif.config.HttpClientConfig;
import com.mawai.ghgif.gifphy.GiphyApiService;
import com.mawai.ghgif.vo.GiphyGifVO;
import com.mawai.ghgif.vo.GiphyResponseVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Giphy API 服务实现类
 *
 * @author Mawai
 * @version 1.0
 * @since 2025-11-17
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GiphyApiServiceImpl implements GiphyApiService {

    private final HttpClient httpClient;
    private final HttpClientConfig httpClientConfig;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${giphy.api.key}")
    private String apiKey;

    @Value("${giphy.api.base-url:https://api.giphy.com/v1/gifs}")
    private String baseUrl;

    /**
     * 发送 GET 请求到 Giphy API
     */
    private HttpResponse<String> sendGetRequest(String url) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(httpClientConfig.getRequestTimeout())
                .GET()
                .build();

        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    /**
     * 获取热门 GIF 列表
     *
     * @param limit  返回数量
     * @param offset 偏移量
     * @return GiphyResponseVO - data 是数组，包含 pagination
     */
    @Override
    public GiphyResponseVO getTrending(int limit, int offset) {
        try {
            String url = String.format("%s/trending?api_key=%s&limit=%d&offset=%d&rating=g&lang=zh-CN",
                    baseUrl, apiKey, limit, offset);

            HttpResponse<String> response = sendGetRequest(url);

            if (response.statusCode() == 200) {
                return objectMapper.readValue(response.body(), GiphyResponseVO.class);
            }

            log.error("Giphy trending API 调用失败: HTTP {}", response.statusCode());
            return null;
        } catch (Exception e) {
            log.error("获取热门 GIF 失败: {}", e.getMessage(), e);
            return null;
        }
    }

    /**
     * 搜索 GIF
     *
     * @param query  搜索关键词
     * @param limit  返回数量
     * @param offset 偏移量
     * @return GiphyResponseVO - data 是数组，包含 pagination
     */
    @Override
    public GiphyResponseVO search(String query, int limit, int offset) {
        try {
            String encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8);
            String url = String.format("%s/search?api_key=%s&q=%s&limit=%d&offset=%d&rating=g&lang=zh-CN",
                    baseUrl, apiKey, encodedQuery, limit, offset);

            HttpResponse<String> response = sendGetRequest(url);

            if (response.statusCode() == 200) {
                return objectMapper.readValue(response.body(), GiphyResponseVO.class);
            }

            log.error("Giphy search API 调用失败: HTTP {}", response.statusCode());
            return null;
        } catch (Exception e) {
            log.error("搜索 GIF 失败: query={}, error={}", query, e.getMessage(), e);
            return null;
        }
    }

    /**
     * 获取随机 GIF
     *
     * @param tag 可选标签
     * @return GiphyGifVO - 返回单个对象，无 pagination
     */
    @Override
    public GiphyGifVO getRandom(String tag) {
        try {
            String url = String.format("%s/random?api_key=%s&rating=g", baseUrl, apiKey);
            if (tag != null && !tag.isEmpty()) {
                String encodedTag = URLEncoder.encode(tag, StandardCharsets.UTF_8);
                url += "&tag=" + encodedTag;
            }

            HttpResponse<String> response = sendGetRequest(url);

            if (response.statusCode() == 200) {
                // Random API 返回的是 { "data": GifObject, "meta": ... } 而不是 GifObject[]
                var jsonNode = objectMapper.readTree(response.body());
                return objectMapper.treeToValue(jsonNode.get("data"), GiphyGifVO.class);
            }

            log.error("Giphy random API 调用失败: HTTP {}", response.statusCode());
            return null;
        } catch (Exception e) {
            log.error("获取随机 GIF 失败: tag={}, error={}", tag, e.getMessage(), e);
            return null;
        }
    }

    /**
     * 根据 Giphy ID 获取单个 GIF
     *
     * @param giphyId Giphy GIF ID
     * @return GiphyGifVO - 返回单个对象，无 pagination
     */
    @Override
    public GiphyGifVO getGifByGiphyId(String giphyId) {
        try {
            String url = String.format("%s/%s?api_key=%s&rating=g", baseUrl, giphyId, apiKey);

            HttpResponse<String> response = sendGetRequest(url);

            if (response.statusCode() == 200) {
                var jsonNode = objectMapper.readTree(response.body());
                return objectMapper.treeToValue(jsonNode.get("data"), GiphyGifVO.class);
            }

            log.error("Giphy get by id API 调用失败: HTTP {}", response.statusCode());
            return null;
        } catch (Exception e) {
            log.error("根据ID获取 GIF 失败: giphyId={}, error={}", giphyId, e.getMessage(), e);
            return null;
        }
    }

    /**
     * 根据多个 Giphy ID 批量获取 GIF
     *
     * @param giphyIds Giphy GIF ID 列表
     * @return GiphyResponseVO - data 是数组，包含 pagination
     */
    @Override
    public GiphyResponseVO getGifsByGiphyIds(List<String> giphyIds) {
        try {
            if (giphyIds == null || giphyIds.isEmpty()) {
                return null;
            }

            String ids = String.join(",", giphyIds);
            String url = String.format("%s?api_key=%s&ids=%s&rating=g", baseUrl, apiKey, ids);

            HttpResponse<String> response = sendGetRequest(url);

            if (response.statusCode() == 200) {
                return objectMapper.readValue(response.body(), GiphyResponseVO.class);
            }

            log.error("Giphy get by ids API 调用失败: HTTP {}", response.statusCode());
            return null;
        } catch (Exception e) {
            log.error("批量获取 GIF 失败: ids={}, error={}", giphyIds, e.getMessage(), e);
            return null;
        }
    }
}