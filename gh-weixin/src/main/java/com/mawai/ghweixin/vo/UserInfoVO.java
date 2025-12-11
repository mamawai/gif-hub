package com.mawai.ghweixin.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 用户信息数据传输对象
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserInfoVO {
    
    /**
     * 用户ID
     */
    private Long userId;
    
    /**
     * 邮箱
     */
    private String email;
    
    /**
     * 昵称
     */
    private String nickname;
}