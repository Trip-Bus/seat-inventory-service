# Seat Inventory Service

El corazón de la concurrencia de TripBus: estado de cada asiento, retenciones con TTL, bloqueos
distribuidos y confirmación de venta. **Ningún asiento se vende dos veces.**

**HU:** US-016, US-017, US-018, US-022, US-025, US-026, US-080 · **Puerto:** 16003 · **BD:** `tripbus_inventory`

## Tres líneas de defensa contra la doble venta

1. **Lock distribuido en Redis** por asiento (`lock:{itinerario:<id>}:asiento:<n>`, TTL 15 s, token + liberación con Lua), adquiridos en orden ascendente; si no obtiene todos en 500 ms libera y responde 409.
2. **Base de datos**: único `(itinerary_id, seat_number)` y un *trigger* que rechaza revender o retener un asiento `VENDIDO`.
3. **`SELECT … FOR UPDATE`** en orden determinista dentro de la transacción.

Si Redis cae, el servicio sigue funcionando con las líneas 2 y 3 (sin sobreventa).

## API pública (vía API Gateway)

| Método | Endpoint | Doc |
|---|---|---|
| GET | `/seats/itineraries/{id}` | GetSeatStates (estados + resumen; `propio` marca los del usuario) |
| GET | `/seats/itineraries/{id}/{asiento}` | GetSeatState |
| GET | `/seats/availability?itineraryIds=a,b` | disponibilidad para la búsqueda |
| POST | `/seats/holds` `{itineraryId, seatNumbers}` | HoldSeats — 201, o 409 `SEAT_UNAVAILABLE`/`SEAT_LOCKED` |
| GET | `/seats/holds/{id}` | retención con `segundosRestantes` (cuenta regresiva) |
| POST | `/seats/holds/{id}/extend` | ExtendHold (una vez, +5 min) |
| DELETE | `/seats/holds/{id}` | ReleaseHold |

API interna (`X-Internal-Token`): `POST /internal/holds`, `GET /internal/holds/{id}`, `POST /internal/holds/{id}/confirm` (ConfirmSale), `POST /internal/holds/{id}/release`, `POST /internal/offers/{offerId}/seats`, `POST /internal/offers/{offerId}/release`.

**Publica:** `SeatHeld`, `SeatReleased`, `SeatSold`, `HoldExpired`, `SeatStateChanged`.
**Consume:** `ItineraryPublished` (crea los asientos), `ItineraryCancelled`, `PaymentFailed`, `OfferClosed`.

`ReleaseExpiredHolds` corre cada 5 s en un solo nodo (lock de tarea en Redis).

## US-080 (gate del pipeline)

`SeatConcurrencyIT`: 500 hilos compiten por el mismo asiento; exactamente uno lo retiene. En CI se repite 100 veces:

```bash
TRIPBUS_IT=true CONCURRENCY_THREADS=500 CONCURRENCY_RUNS=100 mvn test
```

## Ejecutar

Toda la plataforma: `cd ../API-gateway && docker compose up -d --build` (ver README de API-gateway).

Solo este servicio (con PostgreSQL y Redis del compose levantados):

```bash
mvn spring-boot:run          # puerto 16003
```

## Pruebas

```bash
mvn test                     # unitarias
TRIPBUS_IT=true mvn test     # + integración contra PostgreSQL/Redis reales (base tripbus_inventory_test)
```

El pipeline `.github/workflows/ci-cd.yml` corre todas las pruebas y publica la imagen en GHCR.
