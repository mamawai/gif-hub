package com.mawai.ghmbplus.dao;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mawai.ghmbplus.model.WechatUser;
import org.apache.ibatis.annotations.Mapper;

/**
 * <p>
 * 微信用户表 Mapper 接口
 * </p>
 *
 * @author mawai
 * @since 2025-12-27
 */
@Mapper
public interface WechatUserMapper extends BaseMapper<WechatUser> {

}
