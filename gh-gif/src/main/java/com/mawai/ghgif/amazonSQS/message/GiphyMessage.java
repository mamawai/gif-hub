package com.mawai.ghgif.amazonSQS.message;

import com.mawai.ghgif.dto.GiphyDTO;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

/**
 * Giphy GIF 添加消息
 * 用于从 Giphy 引入 GIF 到系统
 *
 * @author mawai
 * @since 2025-11-20
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GiphyMessage implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * Giphy GIF 数据
     */
    private GiphyDTO giphyDTO;

    /**
     * 添加的用户ID
     */
    private Long userId;

    /**
     * 默认喜欢的分类ID
     */
    private Long categoryId;
}