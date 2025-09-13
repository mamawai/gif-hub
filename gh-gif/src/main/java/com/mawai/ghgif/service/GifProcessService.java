package com.mawai.ghgif.service;

import com.mawai.ghgif.vo.GifVO;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.tomcat.util.http.fileupload.FileUploadException;

import com.mawai.ghgif.dto.GifDTO;

import java.util.List;

/**
 * GIF处理服务接口
 */
public interface GifProcessService {

    /**
     * 上传单个GIF文件
     * @param gifDTO GIF上传请求
     *
     * @return 文件访问URL
     * @throws FileUploadException 文件上传异常
     */
    String r2uploadGif(GifDTO gifDTO) throws FileUploadException;
    
    /**
     * 批量上传GIF文件
     * @param gifsDTO GIF文件列表
     *
     * @return 文件访问URL列表
     */
    List<String> r2batchUploadGif(List<GifDTO> gifsDTO);
    
    /**
     * 删除GIF文件
     * @param fileId 文件ID
     * @return 是否删除成功
     */
    boolean deleteGif(String fileId);
    
    /**
     * 获取用户上传的GIF文件列表
     * @param userId 用户ID
     * @param page 页码
     * @param pageSize 每页数量
     * @return GIF文件URL列表
     */
    Pair<List<GifVO>, Long> listGifsByUser(Long userId, Integer page, Integer pageSize);
    
    /**
     * 更新GIF下载次数
     * @param fileName 文件名
     * @return 是否更新成功
     */
    boolean updateDownloadCount(String fileName);

    /**
     * 更新GIF点赞次数
     * @param fileId 文件ID
     * @param userLikeCategoryId 用户喜欢分类ID
     * @param userId 用户ID
     * @param isLike 点赞还是取消点赞
     * @return 是否更新成功
     */
    boolean updateLikeCount(String fileId, Long userLikeCategoryId, Long userId, Boolean isLike);

    /**
     * 按分类分页获取用户喜欢列表
     * @param userId 用户ID
     * @param categoryId 分类ID
     * @return 用户喜欢列表
     */
    List<GifVO> listUserLikes(Long userId, Long categoryId, Integer pageNum, Integer pageSize);

    /**
     * 获取GIF总数（从Redis缓存）
     * @return GIF总数
     */
    Long getTotalGifCount();

    /**
     * 判断用户是否喜欢此GIF
     * @param fileId 文件ID
     * @param userId 用户ID
     * @return 是否喜欢
     */
    boolean isLikeThis(String fileId, Long userId);

    /**
     * 随机获取GIF列表
     * @param lastId 最后一个GIF的ID
     * @return 随机GIF列表
     */
    List<GifVO> getRandomGifs(String lastId);

    /**
     * 获取随机GIF
     * @return 随机GIF
     */
    GifVO getRandomGif();

    /**
     * 更新GIF查看次数
     * @param fileId 文件名
     * @return 是否更新成功
     */
    boolean updateViewCount(String fileId);
}
