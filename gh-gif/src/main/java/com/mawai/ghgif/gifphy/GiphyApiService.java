package com.mawai.ghgif.gifphy;

import com.mawai.ghgif.vo.GiphyGifVO;
import com.mawai.ghgif.vo.GiphyResponseVO;

import java.util.List;

/**
 * Giphy API 服务接口
 *
 * @author Mawai
 * @version 1.0
 * @since 2025-11-17
 */
public interface GiphyApiService {
    
    /**
     * 获取热门 GIF
     *
     * @param limit 返回数量
     * @param offset 偏移量
     * @return Giphy 响应
     */
    GiphyResponseVO getTrending(int limit, int offset);
    
    /**
     * 搜索 GIF
     *
     * @param query 搜索关键词
     * @param limit 返回数量
     * @param offset 偏移量
     * @return Giphy 响应
     */
    GiphyResponseVO search(String query, int limit, int offset);
    
    /**
     * 获取随机 GIF
     *
     * @param tag 标签（可选）
     * @return 单个 GIF
     */
    GiphyGifVO getRandom(String tag);
    
    /**
     * 根据 Giphy ID 获取单个 GIF
     *
     * @param giphyId Giphy GIF ID
     * @return 单个 GIF
     */
    GiphyGifVO getGifByGiphyId(String giphyId);
    
    /**
     * 根据多个 Giphy ID 批量获取 GIF
     *
     * @param giphyIds Giphy GIF ID 列表
     * @return GIF 列表
     */
    GiphyResponseVO getGifsByGiphyIds(List<String> giphyIds);
}
