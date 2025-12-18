package com.mawai.ghgif.vo;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@EqualsAndHashCode(callSuper = true)
@Data
@NoArgsConstructor
public class UserLikeVO extends GifVO{
    private LocalDateTime userLikeTime; // 用户点赞时间 只用到天就行
    
    public UserLikeVO(GifVO gifVO, LocalDateTime userLikeTime) {
        this.setId(gifVO.getId());
        this.setGiphyId(gifVO.getGiphyId());
        this.setHeight(gifVO.getHeight());
        this.setWidth(gifVO.getWidth());
        this.setTitle(gifVO.getTitle());
        this.setDescription(gifVO.getDescription());
        this.setSource(gifVO.getSource());
        this.setGiphyUsername(gifVO.getGiphyUsername());
        this.setLikeCount(gifVO.getLikeCount());
        this.setDownloadCount(gifVO.getDownloadCount());
        this.setViewCount(gifVO.getViewCount());
        this.setUserId(gifVO.getUserId());
        this.setCreatedAt(gifVO.getCreatedAt());
        this.userLikeTime = userLikeTime;
    }
}
