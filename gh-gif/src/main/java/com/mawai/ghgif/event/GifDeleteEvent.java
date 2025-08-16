package com.mawai.ghgif.event;

import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serial;
import java.io.Serializable;

@Data
@Accessors(chain = true)
public class GifDeleteEvent implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 删除总数量
     */
    private Long delCount;

    /**
     * 批量删除数量
     */
    private int batchSize;
}
