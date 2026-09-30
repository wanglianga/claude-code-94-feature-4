package com.logpark.coldchain.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI 3.x 文档：/v3/api-docs，Swagger UI：/swagger-ui.html。
 * 全部业务接口使用 HTTP Basic。
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI coldChainOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("物流园冷链车辆预约入场与温控异常协同服务 API")
                        .version("1.0.0")
                        .description("""
                                覆盖：预约入场、闸口/月台/质检、冷链事件时间线、
                                温度区间复盘（离线 vs 超温/区间合并）、异常工单并行补证（四方角色证据版本化）、
                                客户部分收/拒与冲突升级审批、赔付候选方案版本化与审批后生效、一页式审计。
                                鉴权：HTTP Basic（点击 Authorize 输入 用户名/密码）。"""))
                .components(new Components().addSecuritySchemes("basicAuth",
                        new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("basic")))
                .addSecurityItem(new SecurityRequirement().addList("basicAuth"));
    }
}
