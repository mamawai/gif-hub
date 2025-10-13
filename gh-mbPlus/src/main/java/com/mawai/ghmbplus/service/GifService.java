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

   /**
    * 随机获取一个GIF
    *
    * @return GIF对象
    */
   Gif getRandomOne();

   /**
    * 插入一条GIF记录
    *
    * @param gif GIF对象
    * @return 是否插入成功
    */
   Boolean insertOne(Gif gif);
}
