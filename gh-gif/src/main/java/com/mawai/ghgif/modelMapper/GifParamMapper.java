package com.mawai.ghgif.modelMapper;

import com.mawai.ghgif.vo.GifVO;
import com.mawai.ghmbplus.model.Gif;
import org.mapstruct.Mapper;

@Mapper(componentModel = "spring")
public interface GifParamMapper {

    /**
     * Gif to GifVO
     */
    GifVO toGifVO(Gif gif);
}
