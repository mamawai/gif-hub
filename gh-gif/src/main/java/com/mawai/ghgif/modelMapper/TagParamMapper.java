package com.mawai.ghgif.modelMapper;

import com.mawai.ghgif.vo.GifTagVO;
import com.mawai.ghmbplus.dto.GifTagBO;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.util.List;

@Mapper(componentModel = "spring")
public interface TagParamMapper {

    /**
     * GifTagBO to GifTagVO
     */
    @Mapping(source = "tagId", target = "id")
    @Mapping(source = "tagName", target = "name")
    GifTagVO boToTagVO(GifTagBO gifTagBO);

    List<GifTagVO> bosToTagVOs(List<GifTagBO> gifTagBOs);
}
