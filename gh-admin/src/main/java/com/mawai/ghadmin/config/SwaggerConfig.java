package com.mawai.ghadmin.config;

import com.mawai.ghcommon.config.BaseSwaggerConfig;
import com.mawai.ghcommon.domain.SwaggerProperties;
import io.swagger.v3.oas.models.OpenAPI;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Swagger 配置类
 */
@Configuration
public class SwaggerConfig extends BaseSwaggerConfig {

    @Override
    public SwaggerProperties swaggerProperties() {
        return SwaggerProperties.builder()
                .apiBasePackage("com.mawai.ghadmin.controller")
                .title("GifHub管理后台API")
                .description("GifHub管理后台接口文档")
                .contactName("mawai")
                .version("1.0")
                .enableSecurity(false)
                .build();
    }

    @Bean
    public OpenAPI customOpenApi() {
        return createOpenApi();
    }
} 