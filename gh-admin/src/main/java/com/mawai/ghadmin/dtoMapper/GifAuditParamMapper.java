package com.mawai.ghadmin.dtoMapper;

import com.mawai.ghadmin.dto.GifAuditDTO;
import com.mawai.ghmbplus.model.GifAudit;
import org.mapstruct.Mapper;

@Mapper
public interface GifAuditParamMapper {

    GifAuditDTO toGifAuditDTO (GifAudit gifAudit);
}
