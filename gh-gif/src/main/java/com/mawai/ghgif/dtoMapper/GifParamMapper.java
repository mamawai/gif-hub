package com.mawai.ghgif.dtoMapper;

import com.mawai.ghgif.dto.GifDTO;
import com.mawai.ghmbplus.model.Gif;
import org.mapstruct.Mapper;

@Mapper
public interface GifParamMapper {

    GifDTO toGifDTO(Gif gif);
}
