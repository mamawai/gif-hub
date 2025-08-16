package com.mawai.ghmbplus.service.impl;

import com.baomidou.mybatisplus.extension.toolkit.SqlHelper;
import com.mawai.ghmbplus.model.Gif;
import com.mawai.ghmbplus.dao.GifMapper;
import com.mawai.ghmbplus.service.GifService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.concurrent.ThreadLocalRandom;

/**
 * <p>
 * GIF资源表 服务实现类
 * </p>
 *
 * @author mawai
 * @since 2025-07-14
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GifServiceImpl extends ServiceImpl<GifMapper, Gif> implements GifService {

    @Override
    public Gif getRandomOne() {
        try {
            // 生成 [0, 1) 范围内的随机数
            double randomValue = ThreadLocalRandom.current().nextDouble();
            // 用这个随机数去查询
            Gif gif = baseMapper.selectRandom(randomValue);
            // 如果不为空，则返回比它大的第一条数据
            if (gif != null) {
                return gif;
            } else {
                // 如果为空，则返回随机值最小的那一行
                log.error("无法从数据库中获取随机GIF，返回随机值最小的那一行");
                return baseMapper.selectMinRandom();
            }
        } catch (Exception e) {
            log.error("查询出现异常，返回随机值最小的那一行: {}", e.getMessage());
            return baseMapper.selectMinRandom();
        }
    }

    /**
     * 插入一条数据，生成随机数
     *
     * @param gif GIF对象
     * @return 插入结果
     */
    @Override
    public Boolean insertOne(Gif gif) {
        return SqlHelper.retBool(baseMapper.insertOneWithRandomValue(gif));
    }
}
