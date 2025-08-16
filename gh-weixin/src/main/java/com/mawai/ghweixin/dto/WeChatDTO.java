package com.mawai.ghweixin.dto;

import lombok.Data;
/**
 * DTO 前端传给后端的参数
 * VO 后端返回给前端的参数
 * PO 数据库对应的实体类。也可以直接写名字比如 User
 */
@Data
public class WeChatDTO {
    /**
     * 微信token
     */
    private String code;
}
