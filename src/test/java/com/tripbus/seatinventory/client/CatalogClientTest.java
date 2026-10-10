package com.tripbus.seatinventory.client;

import com.sun.net.httpserver.HttpServer;
import com.tripbus.seatinventory.config.InventoryProperties;
import com.tripbus.seatinventory.config.RequestIdFilter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/** CatalogClient contra un servidor HTTP local que simula catalog. */
class CatalogClientTest {

    private static final UUID ITINERARIO = UUID.fromString("11111111-1111-1111-1111-111111111111");

    private HttpServer server;
    private final AtomicReference<String> requestIdRecibido = new AtomicReference<>();
    private CatalogClient client;

    @BeforeEach
    void levantarCatalogFalso() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/itineraries/", exchange -> {
            requestIdRecibido.set(exchange.getRequestHeaders().getFirst(RequestIdFilter.HEADER));
            if (exchange.getRequestURI().getPath().equals("/itineraries/" + ITINERARIO + "/seatmap")) {
                byte[] body = ("{\"itineraryId\":\"" + ITINERARIO + "\",\"capacidad\":2,\"asientos\":[\"1\",\"2\"]}")
                        .getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(body);
                }
            } else {
                exchange.sendResponseHeaders(404, -1);
                exchange.close();
            }
        });
        server.start();
        client = new CatalogClient(propiedades("http://localhost:" + server.getAddress().getPort()));
    }

    @AfterEach
    void detener() {
        server.stop(0);
        MDC.clear();
    }

    private static InventoryProperties propiedades(String catalogUrl) {
        return new InventoryProperties(null, null, 0, null, null, catalogUrl);
    }

    @Test
    void devuelveElMapaYPropagaElRequestIdDeLaPeticionEnCurso() {
        MDC.put(RequestIdFilter.MDC_KEY, "prueba-seat-1");

        var mapa = client.seatMap(ITINERARIO);

        assertThat(mapa).hasValueSatisfying(m -> {
            assertThat(m.capacidad()).isEqualTo(2);
            assertThat(m.asientos()).containsExactly("1", "2");
        });
        assertThat(requestIdRecibido.get()).isEqualTo("prueba-seat-1");
    }

    @Test
    void itinerarioInexistenteDevuelveVacio() {
        assertThat(client.seatMap(UUID.randomUUID())).isEmpty();
        assertThat(requestIdRecibido.get()).isNull();
    }

    @Test
    void catalogCaidoDevuelveVacioSinLanzar() {
        CatalogClient sinCatalog = new CatalogClient(propiedades("http://localhost:1"));

        assertThat(sinCatalog.seatMap(ITINERARIO)).isEmpty();
    }
}
