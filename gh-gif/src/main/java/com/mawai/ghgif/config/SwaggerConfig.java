package com.mawai.ghgif.config;

import com.mawai.ghcommon.config.BaseSwaggerConfig;
import com.mawai.ghcommon.domain.SwaggerProperties;
import io.swagger.v3.oas.models.OpenAPI;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SwaggerConfig extends BaseSwaggerConfig {


    @Override
    public SwaggerProperties swaggerProperties() {
        return SwaggerProperties.builder()
                .apiBasePackage("com.mawai.ghgif.controller")
                .title("ghgif-api")
                .description("ghgif搜索相关接口文档")
                .contactName("mawai")
                .version("1.0")
                .enableSecurity(true)
                .build();
    }

    @Bean
    public OpenAPI customOpenApi() {
        return createOpenApi();
    }
}
