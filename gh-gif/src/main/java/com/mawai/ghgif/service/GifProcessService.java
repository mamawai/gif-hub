package com.mawai.ghgif.service;

import org.apache.commons.lang3.tuple.Pair;
import org.apache.tomcat.util.http.fileupload.FileUploadException;
import org.springframework.web.multipart.MultipartFile;

import com.mawai.ghgif.dto.GifDTO;

import java.io.IOException;
import java.util.List;

/**
 * GIF处理服务接口
 */
public interface GifProcessService {

    /**
     * 上传单个GIF文件（包含压缩处理）
     * @param file 文件对象
     * @param userId 用户ID
     * @param title 标题（可选）
     * @param description 描述（可选）
     * @return 文件访问URL
     * @throws FileUploadException IO异常
     */
    String r2uploadGif(MultipartFile file, Long userId, String title, String description) throws FileUploadException;
    
    /**
     * 批量上传GIF文件（包含压缩处理）
     * @param files 文件对象列表
     * @param userId 用户ID
     * @param titles 标题列表
     * @param descriptions 描述列表
     * @return 文件访问URL列表
     * @throws IOException IO异常
     */
    List<String> batchUploadGif(List<MultipartFile> files, Long userId, List<String> titles, List<String> descriptions) throws IOException;
    
    /**
     * 删除GIF文件
     * @param fileId 文件ID
     * @return 是否删除成功
     */
    boolean deleteGif(String fileId);
    
    /**
     * 获取GIF文件列表
     * @param page 页码
     * @param pageSize 每页数量
     * @return GIF文件URL列表
     */
    List<GifDTO> listGifs(Integer page, Integer pageSize);
    
    /**
     * 获取用户上传的GIF文件列表
     * @param userId 用户ID
     * @param page 页码
     * @param pageSize 每页数量
     * @return GIF文件URL列表
     */
    Pair<List<GifDTO>, Long> listGifsByUser(Long userId, Integer page, Integer pageSize);
    
    /**
     * 更新GIF下载次数
     * @param fileName 文件名
     * @return 是否更新成功
     */
    boolean updateDownloadCount(String fileName);

    /**
     * 更新GIF点赞次数
     * @param fileId 文件ID
     * @param userId 用户ID
     * @param isLike 点赞还是取消点赞
     * @return 是否更新成功
     */
    boolean updateLikeCount(String fileId, Long userId, Boolean isLike);

    /**
     * 获取用户喜欢列表
     * @param userId 用户ID
     * @return 用户喜欢列表
     */
    List<GifDTO> getUserLikeList(Long userId);

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
}
