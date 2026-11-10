package com.tripbus.seatinventory.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.info.BuildProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * Documentación OpenAPI / Swagger UI (http://localhost:18003/swagger-ui.html). Solo en IDE y QA:
 * application-prod.yml apaga springdoc. El mapa de asientos y la disponibilidad son públicos; para
 * /seats/holds el botón "Authorize" acepta el accessToken que emite identity en /auth/login.
 * /internal/** no usa JWT sino el header X-Internal-Token.
 *
 * <p>HT-10: con OPENAPI_SERVER_URL (tripbus.openapi.server-url) el documento anuncia esa URL como servidor
 * (p. ej. la del API Gateway, para que "Try it out" pase por él); vacía, springdoc usa la del servicio.
 */
@Configuration
public class OpenApiConfig {

    private static final String BEARER = "bearer-jwt";

    @Bean
    public OpenAPI seatInventoryOpenApi(ObjectProvider<BuildProperties> buildProperties,
                                        @Value("${tripbus.openapi.server-url:}") String serverUrl) {
        BuildProperties build = buildProperties.getIfAvailable();
        String version = build != null ? build.getVersion() : "dev";
        OpenAPI openApi = new OpenAPI()
                .info(new Info()
                        .title("TripBus · Seat Inventory")
                        .description("Estado de los asientos, retenciones con TTL, bloqueos distribuidos y confirmación de venta.")
                        .version(version))
                .components(new Components().addSecuritySchemes(BEARER, new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP)
                        .scheme("bearer")
                        .bearerFormat("JWT")))
                .addSecurityItem(new SecurityRequirement().addList(BEARER));
        if (StringUtils.hasText(serverUrl)) {
            openApi.servers(List.of(new Server().url(serverUrl.trim())));
        }
        return openApi;
    }
}
