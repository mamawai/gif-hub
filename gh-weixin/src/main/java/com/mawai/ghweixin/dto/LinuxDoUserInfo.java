package com.mawai.ghweixin.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data
public class LinuxDoUserInfo {

    private Long id;

    private String username;

    private String name;

    @JsonProperty("trust_level")
    private Integer trustLevel;

    private Boolean active;
}
