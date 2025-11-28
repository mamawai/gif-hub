package com.mawai.ghgif.service;

import com.mawai.ghgif.vo.GifVO;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.tomcat.util.http.fileupload.FileUploadException;

import com.mawai.ghgif.dto.GifDTO;
import com.mawai.ghgif.dto.GiphyDTO;

import java.util.List;

/**
 * GIF处理服务接口
 *
 * @author mawai
 */
public interface GifProcessService {

    /**
     * 上传单个GIF文件到R2
     *
     * @param gifDTO GIF上传请求
     * @return 文件访问URL
     */
    String r2uploadGif(GifDTO gifDTO) throws FileUploadException;
    
    /**
     * 批量上传GIF文件到R2
     *
     * @param gifsDTO GIF文件列表
     * @return 文件访问URL列表
     */
    List<String> r2batchUploadGif(List<GifDTO> gifsDTO);
    
    /**
     * 删除GIF文件
     *
     * @param fileId 文件ID
     * @return 是否删除成功
     */
    boolean deleteGif(String fileId);
    
    /**
     * 分页获取用户上传的GIF列表
     *
     * @param userId 用户ID
     * @param page 页码
     * @param pageSize 每页数量
     * @return GIF列表及总数
     */
    Pair<List<GifVO>, Long> listGifsByUser(Long userId, Integer page, Integer pageSize);
    
    /**
     * 更新GIF下载次数
     *
     * @param fileId 文件ID
     * @return 是否更新成功
     */
    boolean updateDownloadCount(String fileId);

    /**
     * 更新GIF点赞状态
     *
     * @param fileId 文件ID
     * @param userLikeCategoryId 用户喜欢分类ID
     * @param userId 用户ID
     * @param isLike true-点赞，false-取消点赞
     * @return 是否操作成功
     */
    boolean toggleGifLike(String fileId, Long userLikeCategoryId, Long userId, Boolean isLike);

    /**
     * 按分类分页获取用户喜欢的GIF列表
     *
     * @param userId 用户ID
     * @param categoryId 分类ID
     * @param pageNum 页码
     * @param pageSize 每页数量
     * @return 用户喜欢的GIF列表
     */
    List<GifVO> listUserLikes(Long userId, Long categoryId, Integer pageNum, Integer pageSize);

    /**
     * 获取GIF总数
     *
     * @return GIF总数
     */
    Long getTotalGifCount();

    /**
     * 判断用户是否喜欢此GIF
     *
     * @param fileId 文件ID
     * @param userId 用户ID
     * @return true-已喜欢，false-未喜欢
     */
    boolean isLikeThis(String fileId, Long userId);

    /**
     * 随机获取GIF列表
     *
     * @param lastId 上次查询最后一个GIF的ID，用于分页加载
     * @return 随机GIF列表
     */
    List<GifVO> getRandomGifs(String lastId);

    /**
     * 随机获取一个GIF
     *
     * @return 随机GIF
     */
    GifVO getRandomGif();

    /**
     * 更新GIF查看次数
     *
     * @param fileId 文件ID
     * @return 是否更新成功
     */
    boolean updateViewCount(String fileId);

    /**
     * 根据GIF ID查询单个GIF详情
     *
     * @param gifId GIF的ID
     * @return GIF详情
     */
    GifVO getGifById(Long gifId);

    /**
     * 添加 GIF 到 what we like
     * 发送消息到 SQS，由 GiphyMessageConsumer 异步处理
     *
     * @param giphyDTO Giphy DTO
     * @param userId 用户ID
     * @param categoryId 默认喜欢的分类ID
     */
    void addGifToWhatWeLike(GiphyDTO giphyDTO, Long userId, Long categoryId);

    /**
     * 更新用户昵称
     *
     * @param userId 用户ID
     * @param nickname 新昵称
     * @return 是否更新成功
     */
    boolean updateUserNickname(Long userId, String nickname);
}
