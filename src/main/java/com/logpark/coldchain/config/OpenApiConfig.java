package com.logpark.coldchain.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI 3.x 文档与 Swagger UI（/swagger-ui.html）。
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI coldchainOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("物流园冷链车辆预约入场与温控异常协同服务")
                        .version("1.1.0")
                        .description("预约→闸口→月台→质检→异常多方协同→处置定责结算→客户签收追溯；"
                                + "feature-4：冷链事件时间线、异常自动冻结、多方补证复盘、赔付候选与审批链、一页可审计时间线。"
                                + "除 /api/health 外均需 HTTP Basic 认证。"))
                .components(new Components().addSecuritySchemes("basicAuth",
                        new SecurityScheme().type(SecurityScheme.Type.HTTP).scheme("basic")))
                .addSecurityItem(new SecurityRequirement().addList("basicAuth"));
    }
}
