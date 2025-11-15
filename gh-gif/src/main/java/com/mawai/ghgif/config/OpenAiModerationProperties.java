package com.mawai.ghgif.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "openai.moderation")
public class OpenAiModerationProperties {

    private Map<String, Double> thresholds = new HashMap<>();
}
