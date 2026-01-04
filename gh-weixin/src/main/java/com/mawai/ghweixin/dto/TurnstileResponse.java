package com.mawai.ghweixin.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;

@Data
public class TurnstileResponse {
    private boolean success;

    @JsonProperty("error-codes")
    private List<String> errorCodes;

    @JsonProperty("challenge_ts")
    private String challengeTs;

    private String hostname;
}
