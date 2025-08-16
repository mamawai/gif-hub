package com.mawai.ghmbplus.service.impl;

import com.mawai.ghmbplus.model.User;
import com.mawai.ghmbplus.dao.UserMapper;
import com.mawai.ghmbplus.service.UserService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;

/**
 * <p>
 * 用户表 服务实现类
 * </p>
 *
 * @author mawai
 * @since 2025-07-14
 */
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements UserService {

}
