package com.mawai.ghmbplus.service;

import com.mawai.ghmbplus.model.Gif;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * <p>
 * GIF资源表 服务类
 * </p>
 *
 * @author mawai
 * @since 2025-07-14
 */
public interface GifService extends IService<Gif> {

   Gif getRandomOne();

   Boolean insertOne(Gif gif);
}
