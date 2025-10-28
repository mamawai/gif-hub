package com.mawai.ghmbplus.dto;

import lombok.Data;

/**
 * 子评论数量统计 BO
 * 
 * @author mawai
 */
@Data
public class ChildCountBO {
    
    /**
     * 根评论ID
     */
    private Long rootCommentId;
    
    /**
     * 子评论数量
     */
    private Integer childCount;
}

