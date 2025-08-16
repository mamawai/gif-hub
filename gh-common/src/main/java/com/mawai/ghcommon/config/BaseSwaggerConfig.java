package com.mawai.ghcommon.config;

import com.mawai.ghcommon.domain.SwaggerProperties;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springdoc.core.models.GroupedOpenApi;
import org.springframework.context.annotation.Bean;

public abstract class BaseSwaggerConfig {

    public OpenAPI createOpenApi() {
        SwaggerProperties swaggerProperties = swaggerProperties();

        OpenAPI openAPI = new OpenAPI().info(
                new Info()
                        .title(swaggerProperties.getTitle())
                        .description(swaggerProperties.getDescription())
                        .contact(new Contact().name(swaggerProperties.getContactName()))
                        .version(swaggerProperties.getVersion())
        );

        if (swaggerProperties.isEnableSecurity()) {
            openAPI.components(new Components()
                            .addSecuritySchemes("Authorization", createSecurityScheme()))
                    .addSecurityItem(new SecurityRequirement().addList("Authorization"));
        }


        return openAPI;
    }

    private SecurityScheme createSecurityScheme() {
        return new SecurityScheme()
                .name("Authorization")
                .type(SecurityScheme.Type.APIKEY)
                .in(SecurityScheme.In.HEADER);
    }

    @Bean
    public GroupedOpenApi groupedOpenApi() {
        SwaggerProperties swaggerProperties = swaggerProperties();
        return GroupedOpenApi.builder()
                .group("public")
                .packagesToScan(swaggerProperties.getApiBasePackage())
                .build();
    }

    /**
     * 自定义Swagger配置
     */
    public abstract SwaggerProperties swaggerProperties();

}
