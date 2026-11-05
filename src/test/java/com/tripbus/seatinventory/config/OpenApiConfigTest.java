package com.tripbus.seatinventory.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.servers.Server;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.boot.info.BuildProperties;

import static org.assertj.core.api.Assertions.assertThat;

/** HT-10: OPENAPI_SERVER_URL. */
class OpenApiConfigTest {

    private OpenAPI openApi(String serverUrl) {
        return new OpenApiConfig().seatInventoryOpenApi(new DefaultListableBeanFactory().getBeanProvider(BuildProperties.class),
                serverUrl);
    }

    @Test
    void conUrlAnunciaEseServidor() {
        assertThat(openApi(" https://api.tripbus.co/seats ").getServers()).extracting(Server::getUrl)
                .containsExactly("https://api.tripbus.co/seats");
    }

    @Test
    void vaciaDejaQueSpringdocUseLaDelServicio() {
        assertThat(openApi("").getServers()).isNull();
        assertThat(openApi("").getInfo().getVersion()).isEqualTo("dev");
    }
}
