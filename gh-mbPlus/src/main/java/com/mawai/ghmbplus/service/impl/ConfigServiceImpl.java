package com.mawai.ghmbplus.service.impl;

import com.mawai.ghmbplus.model.Config;
import com.mawai.ghmbplus.dao.ConfigMapper;
import com.mawai.ghmbplus.service.ConfigService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;

/**
 * <p>
 * 系统配置表 服务实现类
 * </p>
 *
 * @author mawai
 * @since 2025-07-14
 */
@Service
public class ConfigServiceImpl extends ServiceImpl<ConfigMapper, Config> implements ConfigService {

}
