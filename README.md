# Seat Inventory Service

[![Quality Gate Status](https://sonarcloud.io/api/project_badges/measure?project=Trip-Bus_seat-inventory-service&metric=alert_status)](https://sonarcloud.io/summary/new_code?id=Trip-Bus_seat-inventory-service)

El corazón de la concurrencia de TripBus: estado de cada asiento, retenciones con TTL, bloqueos
distribuidos y confirmación de venta. **Ningún asiento se vende dos veces.**

**Stack:** Java 21 · Spring Boot 3.5.16 · PostgreSQL 16 · Redis 7

**HU:** US-016, US-017, US-018, US-022, US-025, US-026, US-080, US-112, US-119 (soporte), US-139, US-140, US-141, US-142, US-151, US-191, HT-08, HT-10 · **Puerto:** 18003 · **BD:** `tripbus_inventory` (puerto QA 18103)

## Tres líneas de defensa contra la doble venta

1. **Lock distribuido en Redis** por asiento (`lock:{itinerario:<id>}:asiento:<n>`, TTL 15 s, token + liberación con Lua), adquiridos en orden ascendente; si no obtiene todos en 500 ms libera y responde 409.
2. **Base de datos**: único `(itinerary_id, seat_number)` y un *trigger* que rechaza revender, retener, ofertar o bloquear un asiento `VENDIDO` (solo puede volver a `DISPONIBLE`, por una anulación).
3. **`SELECT … FOR UPDATE`** en orden determinista dentro de la transacción.

Si Redis cae, el servicio sigue funcionando con las líneas 2 y 3 (sin sobreventa). Las tres líneas se
aplican a **todas** las operaciones que cambian asientos: retener, vender, ofertar, anular, cambiar de
asiento, bloquear y cambiar el bus.

**Con tramos (US-142)** las tres líneas siguen, ampliadas:

1. Redis: el lock de un pedido por tramo es por asiento **y tramo** (`…:asiento:<n>:tramo:<desde>-<hasta>`); el del
   recorrido completo es la llave de siempre. Dos pedidos del mismo asiento y tramo compiten aquí (fail fast).
2. `SELECT … FOR UPDATE` de la fila del asiento en `seat_states`: ordena a **todos** los que piden ese asiento, sea
   cual sea su tramo, y dentro de la transacción se revisa que ninguna ocupación se cruce.
3. Base de datos: la tabla `seat_occupancies` tiene una restricción `EXCLUDE USING gist (itinerary_id WITH =,
   seat_number WITH =, int4range(desde_orden, hasta_orden) WITH &&)` (extensión `btree_gist`): **dos ocupaciones
   del mismo asiento nunca se cruzan**, aunque la aplicación falle. El trigger también impide pasar un `VENDIDO`
   a `TRAMO`.

## Venta por tramos (US-142)

Una retención o venta ocupa cada asiento en un tramo `[fromOrden, toOrden)` por el orden de las paradas (se sube
en `fromOrden` y se baja en `toOrden`): `[1,3)` y `[3,5)` **no** se cruzan. Sin tramo es el recorrido completo,
`[1, 32767)`, como siempre. `1 <= fromOrden < toOrden <= 32767`; con solo uno de los dos, o mal formado, 400
`INVALID_SEGMENT`. Las ofertas relámpago son del recorrido completo (con tramo, 400).

- **Toda** retención y venta, completa o por tramo, escribe una fila por asiento en `seat_occupancies`
  (`RETENIDO` → `VENDIDO` al confirmar; se borra al liberar, vencer, cancelar o anular; se mueve al cambiar de
  asiento). La migración V7 creó las de las retenciones y ventas que ya existían.
- `seat_states` sigue siendo el estado del recorrido completo, en la misma transacción: una retención/venta
  completa lo deja `RETENIDO`/`VENDIDO` como siempre; si el asiento solo tiene ocupaciones por tramos queda
  **`TRAMO`**, y vuelve a `DISPONIBLE` cuando se le va la última.
- Un asiento está **libre en un tramo** si está `DISPONIBLE` o `TRAMO` y ninguna ocupación lo cruza.
  `EN_OFERTA` y `BLOQUEADO` no se venden por tramos.
- Confirmar, liberar, vencer, anular (US-151) y cambiar de asiento (US-139) respetan el tramo de cada retención:
  el asiento nuevo de un cambio tiene que estar libre en ese tramo. El cambio de bus (US-119) trata un asiento
  `TRAMO` como ocupado (409 `SEATS_CONFLICT` si no existe en el bus nuevo).
- La lista de espera usa el tramo de quien se anotó (cuenta lo libre en ese tramo y le ofrece una retención en él).
- Los eventos de una retención por tramo (`SeatHeld`, `SeatSold`, `SeatReleased`, `HoldExpired`,
  `WaitlistSeatOffered`) traen además `desdeOrden` y `hastaOrden`; los del recorrido completo, igual que antes.

## Estados del asiento

| Estado | Significa | Pasa a |
|---|---|---|
| `DISPONIBLE` | a la venta | `RETENIDO`, `EN_OFERTA`, `BLOQUEADO`; `VENDIDO` solo por un cambio de asiento (US-139) |
| `RETENIDO` | retención de 10 min (+5 una vez) | `VENDIDO` (ConfirmSale), `DISPONIBLE`/`EN_OFERTA` (liberada o expirada) |
| `VENDIDO` | comprado | solo `DISPONIBLE` (anulación US-151 o cambio de asiento US-139) |
| `EN_OFERTA` | reservado para una oferta relámpago | `RETENIDO` (solo por esa oferta), `DISPONIBLE` (OfferClosed) |
| `BLOQUEADO` | la empresa no lo vende (US-112), con motivo | `DISPONIBLE` (desbloqueo) |
| `TRAMO` | retenido o vendido solo en parte del recorrido (US-142) | `DISPONIBLE` (cuando se va su última ocupación por tramo) |

Cada asiento tiene además una **categoría** (US-141, `seat_states.categoria`, `ESTANDAR` por defecto) que llega
con `seats: [{numero, categoria}]` en `ItineraryPublished` y en el cambio de bus; si el evento solo trae
`seatNumbers`, queda `ESTANDAR`. Se normaliza a mayúsculas (`[A-Z0-9_]`, hasta 30); una inválida cuenta como no
enviada. El recargo de cada categoría lo calcula catalog, no este servicio.

Un asiento `BLOQUEADO` no se puede retener ni ofertar y no cuenta en `/seats/availability` (ni en
`disponibles` ni en `total`). La base lo exige con dos restricciones: todo `BLOQUEADO` tiene
`bloqueo_motivo` y ningún otro estado lo tiene.

## API pública (vía API Gateway)

| Método | Endpoint | Doc |
|---|---|---|
| GET | `/seats/itineraries/{id}?from=&to=` | GetSeatStates (estados + `categoria` de cada asiento + resumen con `bloqueados` y `enTramos`; `propio` marca los del usuario). Con `from`/`to` (US-142), el estado de cada asiento **en ese tramo**: `RETENIDO`/`VENDIDO` si una ocupación lo cruza, `DISPONIBLE` si no. Viaje archivado → 410 `ITINERARY_ARCHIVED` |
| GET | `/seats/itineraries/{id}/{asiento}` | GetSeatState |
| GET | `/seats/availability?itineraryIds=a,b&from=&to=` | disponibilidad para la búsqueda (sin los `BLOQUEADO`); con `from`/`to`, los libres en ese tramo (`enOferta` = 0) |
| POST | `/seats/holds` `{itineraryId, seatNumbers, fromOrden?, toOrden?}` | HoldSeats (por tramo si viene, US-142) — 201, o 409 `SEAT_UNAVAILABLE`/`SEAT_LOCKED`; la respuesta trae `fromOrden`/`toOrden` si es por tramo |
| GET | `/seats/holds/{id}` | retención con `segundosRestantes` (cuenta regresiva) |
| POST | `/seats/holds/{id}/extend` | ExtendHold (una vez, +5 min) |
| DELETE | `/seats/holds/{id}` | ReleaseHold |
| POST | `/seats/itineraries/{id}/blocks` `{seatNumbers, motivo}` | US-112: bloquea asientos `DISPONIBLE`; 409 `SEAT_UNAVAILABLE` (con `details.asientos`) si alguno está `RETENIDO`, `VENDIDO` o `EN_OFERTA` (entonces no bloquea ninguno) |
| DELETE | `/seats/itineraries/{id}/blocks` `{seatNumbers}` | US-112: devuelve a la venta los `BLOQUEADO` |
| GET | `/seats/itineraries/{id}/occupancy` | **US-191** ocupación y abandono de compra del viaje (`OPERADOR` de su empresa o `ADMIN`) |
| POST | `/seats/itineraries/{id}/waitlist` `{cantidad, fromOrden?, toOrden?}` | **US-140** anotarse en la lista de espera (1–6 asientos; en un tramo si viene, US-142) — 201, o 409 `SEATS_AVAILABLE`/`WAITLIST_CLOSED`/`ALREADY_IN_WAITLIST`/`ITINERARY_CANCELLED` |
| DELETE | `/seats/itineraries/{id}/waitlist` | **US-140** salir de la lista de espera — 204, o 404 `WAITLIST_NOT_FOUND` |
| GET | `/seats/waitlist/me` | **US-140** mis turnos (los más recientes primero) con `posicion` en la fila |

Los `GET` de `/seats/itineraries/**` y `/seats/availability` son públicos, **salvo** `/seats/itineraries/{id}/occupancy`;
el resto exige JWT de identity.

**Ocupación (US-191).** Responde `{itineraryId, capacidad, disponibles, retenidos, vendidos, enOferta, bloqueados,
porcentajeOcupacion, retenciones: {creadas, activas, confirmadas, expiradas, liberadas, liberadasPorUsuario,
canceladas}, tasaAbandono}`. `porcentajeOcupacion` = `vendidos / capacidad` en % con dos decimales;
`tasaAbandono` = `(expiradas + liberadasPorUsuario) / creadas`, de 0 a 1 con cuatro decimales (0 si no hay
retenciones). No cuenta como abandono lo que liberó el sistema: retenciones `CANCELADA` (viaje cancelado) ni las
liberadas por booking (pago rechazado, reembolso…). `liberadasPorUsuario` son las que el pasajero liberó él mismo
(`DELETE /seats/holds/{id}` o saliendo de la lista de espera con una oferta): `holds.motivo_cierre =
LIBERADA_POR_USUARIO` (columna nueva de V5; las retenciones anteriores quedan en `NULL` y no cuentan como
abandono). Solo el `OPERADOR` con `companyId` = empresa del viaje o un `ADMIN`; a cualquier otro (otra empresa,
pasajero) se le responde 404 `ITINERARY_NOT_FOUND`, como si el viaje no existiera. Las cifras salen de dos
consultas agregadas (`seat_states` y `holds` agrupados por viaje y estado, con los índices
`(itinerary_id, estado)` de V5), sin cargar filas en memoria.

**Bloqueos (US-112):** solo el `OPERADOR` de la empresa del viaje (claim `companyId` = `itineraries.company_id`)
o un `ADMIN`; cualquier otro recibe 403 `NOT_ITINERARY_MANAGER`. `company_id` se llena con el `companyId` de
`ItineraryPublished`; los itinerarios anteriores quedan en `NULL` y solo los gestiona el `ADMIN`. Los dos
endpoints son idempotentes y responden `{cambiados, sinCambio}`. En `GET /seats/itineraries/**` el asiento
aparece como `BLOQUEADO` para todos, pero `motivoBloqueo` solo viene para el operador de la empresa y el ADMIN.

## API interna (`X-Internal-Token`)

| Método | Endpoint | Lo usa | Doc |
|---|---|---|---|
| POST | `/internal/holds` `{itineraryId, seatNumbers, userId, offerId?, fromOrden?, toOrden?}` | flash-offers, booking | HoldSeats para otro servicio (por tramo si viene, US-142) |
| GET | `/internal/holds/{id}` | booking | retención |
| POST | `/internal/holds/{id}/confirm` `{bookingId}` | booking | ConfirmSale (idempotente por `bookingId`) |
| POST | `/internal/holds/{id}/release` `{motivo}` | booking | ReleaseHold |
| POST | `/internal/offers/{offerId}/seats` | flash-offers | pasa asientos a `EN_OFERTA` |
| POST | `/internal/offers/{offerId}/release` | flash-offers | los devuelve a la venta |
| POST | `/internal/sales/void` `{itineraryId, bookingId, seatNumbers, motivo}` | booking | **US-151** anular |
| POST | `/internal/sales/swap` `{itineraryId, bookingId, fromSeat, toSeat}` | booking | **US-139** cambiar de asiento |
| POST | `/internal/itineraries/{id}/remap` `{seatNumbers, capacidad}` o `{seats: [{numero, categoria}], capacidad}` | catalog | **US-119** cambiar el bus (con `seats`, **US-141** categorías) |
| GET | `/internal/occupancy?itineraryIds=a,b,…` | catalog (panel), booking (reportes) | **US-191** ocupación de hasta 200 viajes, mismos campos que la pública, en el orden pedido; los viajes desconocidos se omiten; más de 200 → 400 `TOO_MANY_ITINERARIES` |

**Anular (US-151).** `motivo`: `REPROGRAMACION`, `CANCELACION_EMPRESA`, `REEMBOLSO` o `CAMBIO_BUS`. Cada
asiento pasa de `VENDIDO` a `DISPONIBLE` y se limpian `user_id`, `hold_id`, `vendido_en` (y `offer_id`); el
asiento sale de la retención. Solo se anulan asientos vendidos a ese `bookingId` (vía `holds.booking_id`): si
alguno es de otro dueño, 409 `SEAT_NOT_OWNED` y no se anula ninguno. Es idempotente: repetirla responde 200
con el estado actual (`anulados` vacío). Auditoría con origen `VOID_<motivo>` (guarda la retención de la que
salió el asiento) y eventos `SeatStateChanged` y `SeatReleased` (con `bookingId` y `motivo`).

**Cambiar de asiento (US-139).** En una sola transacción `toSeat` pasa de `DISPONIBLE` a `VENDIDO` con el
mismo comprador, la misma retención y la misma fecha de venta, y `fromSeat` vuelve a `DISPONIBLE`: la reserva
nunca queda con cero asientos ni con dos. Locks de Redis y `FOR UPDATE` sobre los dos asientos en orden
ascendente. `toSeat` no disponible → 409 `SEAT_UNAVAILABLE`; `fromSeat` no es de esa reserva → 409
`SEAT_NOT_OWNED`; mismo asiento → 400 `SAME_SEAT`. Repetir un cambio ya hecho responde el estado actual.
Auditoría con origen `SWAP` en los dos asientos, `SeatStateChanged` de ambos y `SeatReleased` de `fromSeat`
(`motivo: CAMBIO_DE_ASIENTO`).

**Cambiar el bus (US-119).** Aplica la lista de asientos del bus nuevo: agrega como `DISPONIBLE` los que no
existían (auditoría `REMAP` + `SeatStateChanged`), borra los `DISPONIBLE` que ya no existen y actualiza la
capacidad. Responde `{capacidad, agregados, eliminados}`. Si algún asiento `VENDIDO`, `RETENIDO`, `EN_OFERTA`
o `BLOQUEADO` no existe en el bus nuevo no cambia nada y responde 409 `SEATS_CONFLICT` con
`details.asientos` (catalog se la muestra al operador para que primero los reubique). `capacidad` menor que
el número de asientos → 400 `INVALID_CAPACITY`.

Errores con el formato de siempre: `{"error": {"code", "message", "details"}}`.

## Lista de espera (US-140)

Tabla `waitlist` (V5): `id, itinerary_id, user_id, cantidad (1–6), estado, hold_id, creado_en, actualizado_en`,
con **un único turno activo** (`ESPERANDO` u `OFRECIDO`) por `(itinerary_id, user_id)` (índice único parcial).

| Estado | Significa | Pasa a |
|---|---|---|
| `ESPERANDO` | en la fila, en orden de llegada (`creado_en`, `id`) | `OFRECIDO`, `CANCELADO` |
| `OFRECIDO` | se le creó una retención normal (`hold_id`) con los asientos | `CONVERTIDO` (la retención se vendió), `VENCIDO` (venció o se liberó sin pagar), `CANCELADO` (salió de la fila o se canceló el viaje) |
| `CONVERTIDO`, `VENCIDO`, `CANCELADO` | turno cerrado (queda como historial) | — |

**Reglas:**

- **Anotarse** solo si hay **menos** asientos `DISPONIBLE` que la `cantidad` (si no, 409 `SEATS_AVAILABLE` con
  `details.disponibles`: que los retenga directamente) y faltan **más de 30 min** para la salida (si no, 409
  `WAITLIST_CLOSED`). Viaje cancelado → 409 `ITINERARY_CANCELLED`; ya en la fila → 409 `ALREADY_IN_WAITLIST`.
- **Cuando un asiento vuelve a `DISPONIBLE`** (retención vencida o liberada, venta anulada, cambio de asiento,
  asiento desbloqueado, agregado por un cambio de bus o devuelto por una oferta que cerró), después del commit y
  en otro hilo se procesa la fila de ese viaje en orden de llegada: al **primero cuya cantidad cabe** en lo
  disponible se le crea una retención normal (10 min, extensible una vez) a su nombre con los **primeros asientos
  libres** (orden natural), por el mismo camino de `HoldSeats` (las tres líneas de defensa), y su turno pasa a
  `OFRECIDO`. Se repite mientras alguien quepa.
- Cada viaje se procesa **de a uno**: cada oferta corre en su transacción con `pg_advisory_xact_lock` del
  itinerario, así dos liberaciones simultáneas nunca ofrecen el mismo asiento a dos personas. Si otro pasajero
  toma un asiento libre entre la lectura y la retención, se reintenta (hasta 3 veces) con lo que quede libre.
- Si la retención ofrecida **vence sin pagar** (o el pasajero la libera), el turno queda `VENCIDO` y los asientos
  se ofrecen al siguiente; si **se vende** (ConfirmSale), queda `CONVERTIDO`. Se actualiza en la misma
  transacción que cierra la retención.
- **Salir** (`DELETE`) pasa el turno a `CANCELADO`; si ya tenía una oferta, libera esa retención (cuenta como
  liberada por el usuario en US-191).
- **Cierre:** 30 min antes de la salida los turnos `ESPERANDO` pasan a `CANCELADO` (los `OFRECIDO` conservan su
  retención hasta que se pague o venza); al cancelarse el viaje (`ItineraryCancelled`) se cierran todos, también
  los `OFRECIDO` (su retención se cancela). En ambos casos se publica `WaitlistClosed`.
- Un barrido cada 30 s (`WAITLIST_INTERVAL_MS`, en un solo nodo con el lock de tareas) cierra las filas que ya
  deben cerrarse y vuelve a procesar las filas con turnos esperando, por si alguna liberación no alcanzó a
  procesarse.
- `GET /seats/waitlist/me` da la `posicion` (desde 1) entre los turnos `ESPERANDO` del viaje; en los demás
  estados viene `null`.

## Eventos

**Publica** (siempre por el outbox, en la misma transacción del cambio):

| Evento | Cuándo |
|---|---|
| `SeatHeld` | retención creada o extendida |
| `SeatReleased` | asientos liberados: retención liberada/expirada/cancelada, anulación (US-151, con `bookingId` y `motivo`) o `fromSeat` de un cambio (US-139) |
| `SeatSold` | ConfirmSale |
| `HoldExpired` | retención vencida por el barrido |
| `SeatStateChanged` | cada cambio de estado de cada asiento (incluye `BLOQUEADO` y los asientos nuevos de un cambio de bus) |
| `WaitlistSeatOffered` `{userId, itineraryId, holdId, seatNumbers, expiraEn}` | US-140: se le ofrecieron asientos a un turno de la lista de espera (el Realtime Gateway lo lleva al aviso privado del pasajero) |
| `WaitlistClosed` `{itineraryId, userIds}` | US-140: la fila se cerró 30 min antes de la salida o porque se canceló el viaje (`userIds`: los turnos que se cancelaron) |

**Consume:**

| Evento | Qué hace |
|---|---|
| `ItineraryPublished` `{itineraryId, seatNumbers (o seats: [{numero, categoria}]), fechaSalida, companyId}` | crea los asientos (con su categoría si viene `seats`, US-141) y guarda `company_id` (completa empresa y fecha si el itinerario ya existía sin ellas) |
| `ItineraryCancelled` | cierra la lista de espera (`WaitlistClosed`), cancela retenciones activas y libera sus asientos |
| `ItineraryRescheduled` `{itineraryId, fechaSalida}` | actualiza `fecha_salida` (un itinerario desconocido se ignora) |
| `PaymentFailed` | libera la retención |
| `OfferClosed` | los asientos de la oferta vuelven a la venta normal |

`ReleaseExpiredHolds` corre cada 5 s en un solo nodo (lock de tarea en Redis).

## US-080 (gate del pipeline)

`SeatConcurrencyIT`: 500 hilos compiten por el mismo asiento; exactamente uno lo retiene. Corre contra
PostgreSQL y Redis reales levantados por Testcontainers (solo necesita Docker abierto). En local se
repite 5 veces por defecto; el CI exporta `CONCURRENCY_THREADS=500` y `CONCURRENCY_RUNS=100` en
**todos** los eventos (también en `main`):

```bash
CONCURRENCY_THREADS=500 CONCURRENCY_RUNS=100 mvn -B verify
```

Tiempo de referencia: en el CI (runner `ubuntu-latest`, PR #1, 2026-10-09) el paso de pruebas con el
gate de 500 hilos × 100 ejecuciones tardó **3 min 53 s**. En local, 5 ejecuciones tardan unos 6 s
(más el arranque de los contenedores de Testcontainers).

## Flujo de ramas y entornos

```
rama de trabajo --PR--> develop (QA) --PR de release--> main (PROD)
```

- `main` sigue siendo la rama por defecto en GitHub, pero **los PR de trabajo van con base `develop`**.
- `develop` es QA y `main` es PROD: a `main` solo llega código por un PR de release desde `develop`.

| Evento | Pruebas (con US-080) + JaCoCo + Trivy | SonarQube Cloud | Imagen publicada en GHCR |
|---|---|---|---|
| PR con base `develop` | ✔ | ✔ (quality gate) | no |
| push a `develop` (merge de un PR) | ✔ | ✔ (quality gate) | `:qa`, `:develop`, `:sha-<corto>` |
| PR con base `main` (release) | ✔ | — | no |
| push a `main` (merge del release) | ✔ | — | `:prod`, `:latest`, `:sha-<corto>` |
| `workflow_dispatch` | ✔ | solo si se lanza sobre `develop` | no |

**Por qué Sonar solo corre en `develop`:** el plan Free de SonarQube Cloud analiza una única rama,
y la rama principal del proyecto en Sonar es `develop`. Lo que llega a `main` es exactamente el
código que ya pasó el quality gate en `develop`, así que en `main` el CI hace `mvn verify` sin Sonar
y lo indica con un aviso (`::notice::`). El control de cobertura de JaCoCo (`jacoco:check`) y el gate
de concurrencia US-080 sí corren en todas las ramas.

**Cómo hacer un release:**

1. Asegúrate de que el último CI de `develop` esté en verde (pruebas, US-080, cobertura, quality gate
   y Trivy) y de haber probado la imagen `:qa`.
2. Abre un PR con base `main` y rama de origen `develop` (título p. ej. `release: <resumen>`).
3. Cuando el CI del PR pase, haz merge (sin squash, para que `main` y `develop` compartan historial).
   El push a `main` publica la imagen `:prod` / `:latest`.

## Entornos (IDE / QA / PROD)

| Entorno | Cómo corre | Perfil Spring | Secretos |
|---|---|---|---|
| IDE | `mvn spring-boot:run` contra los contenedores de QA | (ninguno) | valores por defecto de QA en `application.yml` |
| QA | Docker Compose en tu computador | `qa` (+ `desplegado`) | `.env` (copia de `.env.example`) |
| PROD | Docker Compose en el servidor | `prod` (+ `desplegado`) | solo en el servidor |

Puertos de este repo (el contenedor escucha en el mismo puerto que publica):

| Servicio | QA (publicado) | Dentro de Docker | PROD |
|---|---|---|---|
| seat-inventory-service | 18003 | 18003 | no se publica (entra por api-gateway 18000) |
| PostgreSQL `tripbus_inventory` / usuario `inventory` | 18103 | 5432 | nunca se publica |
| Redis (compartido, con contraseña) | 18100 | 6379 | nunca se publica |

Otros servicios que usa: identity (JWKS, `IDENTITY_JWKS_URI`, por defecto `http://localhost:18001/.well-known/jwks.json`)
y catalog (`CATALOG_URL`, por defecto `http://localhost:18002`, para obtener el mapa de asientos de un
itinerario). En `qa`/`prod` no tienen valor por defecto y son obligatorias.

### Levantar QA

```bash
cp .env.example .env && docker compose up -d --build
docker compose ps                                   # esperar a "healthy"
curl http://localhost:18003/actuator/health
curl "http://localhost:18003/seats/availability?itineraryIds=$(uuidgen)"
```

Con este compose solo funcionan los `GET` públicos (`/seats/availability`, `/seats/itineraries/**`):
sin catalog no hay itinerarios publicados, así que responden lista vacía o 404 controlado.
Retener asientos (`/seats/holds`) exige un JWT de identity y un itinerario publicado por catalog:
pruébalo con el compose general de **API-gateway**. El servicio llega a identity (18001) y catalog
(18002) de tu PC por `host.docker.internal`.

No lo corras a la vez que el compose general de **API-gateway** (usan los mismos puertos).
`docker compose down` detiene conservando los datos; `down -v` los borra.

### Imágenes Docker (GHCR)

El CI publica `ghcr.io/trip-bus/seat-inventory-service` solo en push (nunca desde un PR):

| Etiqueta | Rama | Uso |
|---|---|---|
| `:qa` (y `:develop`) | `develop` | Última versión de QA. |
| `:prod` (y `:latest`) | `main` | Última versión de PROD; es la que se despliega en el servidor. |
| `:sha-<corto>` | ambas | Versión inmutable de un commit concreto (para fijar o hacer rollback). |

`docker compose up --build` en tu computador construye la imagen local `ghcr.io/trip-bus/seat-inventory-service:qa-local`;
para usar la de QA ya publicada: `docker pull ghcr.io/trip-bus/seat-inventory-service:qa`.

### Correr desde el IDE contra QA

Con el compose de QA arriba, `docker compose stop seat-inventory-service` (libera el 18003) y luego
`mvn spring-boot:run` sin perfil: `application.yml` ya apunta a `localhost:18103` (BD) y
`localhost:18100` (Redis). Para Redis define `REDIS_PASSWORD=tripbus_redis_qa_pw` (el compose de QA
lo exige con contraseña).

### Variables

En `qa` y `prod` se activa el perfil `desplegado`: si falta alguna variable obligatoria el servicio
no arranca y dice cuáles faltan (`RequiredSecretsCheck`).

| Variable | Obligatoria en qa/prod | Por defecto (IDE) |
|---|---|---|
| `DB_PASSWORD` | ✔ | `inventory_qa_pw` |
| `REDIS_PASSWORD` | ✔ | (vacía) |
| `INTERNAL_API_TOKEN` | ✔ | `tripbus_internal_qa_token` |
| `IDENTITY_JWKS_URI` | ✔ | `http://localhost:18001/.well-known/jwks.json` |
| `CATALOG_URL` | ✔ | `http://localhost:18002` |

Opcionales con valor por defecto: `SERVER_PORT` (18003), `DB_HOST` (localhost), `DB_PORT` (18103),
`DB_NAME` (tripbus_inventory), `DB_USERNAME` (inventory), `DB_POOL_SIZE`, `REDIS_HOST` (localhost),
`REDIS_PORT` (18100), `HOLD_TTL` (10m), `WAITLIST_INTERVAL_MS` (30000), `ARCHIVE_AFTER_DAYS` (90),
`OTEL_EXPORTER_OTLP_ENDPOINT` (vacía: no se exportan trazas), `TRACING_SAMPLING` (0.1), `LOG_FORMAT` (`text`;
`json` en PROD) y `OPENAPI_SERVER_URL` (vacía). `DB_HOST` reemplaza a la antigua `DB_URL`.

## Swagger

Solo en IDE y QA (en `prod` springdoc está apagado y responde 404):

- UI: http://localhost:18003/swagger-ui.html
- OpenAPI JSON: http://localhost:18003/v3/api-docs

El mapa de asientos y la disponibilidad son públicos. Para `/seats/holds` haz login en identity
(`/auth/login`) y pega el `accessToken` en **Authorize**. `/internal/**` usa el header
`X-Internal-Token`, no JWT.

## Observabilidad

- `/actuator/health` (con `liveness` y `readiness`), `/actuator/prometheus`.
- `/actuator/info`: versión y fecha del build, y `app.commit` (el commit con el que se construyó la imagen).
- Cada petición lleva un `requestId`: se reutiliza el header `X-Request-Id` (lo manda el API Gateway)
  o se genera uno, y se devuelve en la respuesta. Seat Inventory lo **propaga** en sus llamadas
  salientes (a catalog, `CatalogClient`), así una petición se sigue entre servicios.

**HT-10:**

- **Trazas:** Micrometer Tracing con OpenTelemetry (`micrometer-tracing-bridge-otel`,
  `opentelemetry-exporter-otlp`, versiones de Spring Boot). Se exportan por OTLP/HTTP a
  `${OTEL_EXPORTER_OTLP_ENDPOINT}/v1/traces` (vacía = no se exportan) con muestreo `TRACING_SAMPLING` (0.1 por
  defecto). El contexto viaja por los eventos de Redis Streams: el outbox guarda el `traceparent` W3C de la traza en
  curso (`outbox.trace_parent`, V6), el relay lo publica como campo `traceparent` del mensaje y el consumidor
  procesa el evento en un span hijo de esa traza (con el `itineraryId` en el MDC).
- **Logs:** cada línea lleva `traceId`, `spanId` y `requestId`: `[traceId,spanId] [requestId]` en texto (IDE/QA)
  y, con `LOG_FORMAT=json` (por defecto en PROD), una línea JSON por evento en formato ECS
  (`logging.structured.format.console=ecs`) con todo el MDC.
- **Métricas** (Prometheus):

| Métrica | Qué cuenta |
|---|---|
| `tripbus_retenciones_total{resultado=creada\|confirmada\|expirada\|liberada}` | retenciones creadas, confirmadas (venta), vencidas y liberadas (también las canceladas) |
| `tripbus_lock_fallido_total` | pedidos que no obtuvieron a tiempo los locks de Redis (409 `SEAT_LOCKED`) |
| `tripbus_doble_venta_bloqueada_total` | dobles ventas que rechazó la base (trigger, único o `EXCLUDE` de los tramos): actuó la última línea |
| `tripbus_retencion_segundos` | Timer de HoldSeats (locks + transacción), también si falla |

- **OpenAPI:** con `OPENAPI_SERVER_URL` (p. ej. la URL del API Gateway) Swagger anuncia ese servidor; vacía (por
  defecto), el del propio servicio.

## Archivo de viajes viejos (HT-08)

Un job nocturno (`tripbus.jobs.archive-cron`, 04:15 de Bogotá, en un solo nodo con el lock de tareas) mueve los
viajes que salieron hace más de `tripbus.inventory.archive-after-days` (`ARCHIVE_AFTER_DAYS`, 90 por defecto) a las
tablas `*_archive` (V8, mismo esquema con `LIKE … INCLUDING ALL`): `itineraries`, `seat_states`,
`seat_occupancies`, `holds`, `hold_seats`, `waitlist` y `seat_audit_log`. Va por **lotes de 500 viajes**, cada uno
en su propia transacción corta (copia y borra, todo o nada; `FOR UPDATE SKIP LOCKED` sobre los viajes).

- La ocupación por viaje (`/seats/itineraries/{id}/occupancy`) y `/internal/occupancy` **siguen respondiendo** para
  los viajes archivados (leen también las tablas `*_archive`), porque los reportes de booking abarcan hasta 366
  días.
- El mapa de asientos de un viaje archivado responde 410 `ITINERARY_ARCHIVED` (no se vuelve a inicializar desde
  catalog).
- Una migración que cambie una tabla viva debe cambiar también su `*_archive` (el job copia con `SELECT *`).

## Pruebas

```bash
mvn -B verify      # unitarias + integración con PostgreSQL y Redis reales (Testcontainers: Docker abierto)
```

- `*Test` / `*Tests`: unitarias.
- `SeatConcurrencyIT`: levanta PostgreSQL 16 y Redis 7 con Testcontainers, corre las migraciones Flyway
  reales (incluido el trigger contra la doble venta) y prueba US-080, confirmaciones concurrentes,
  extensión y expiración de retenciones y asientos en oferta.
- `OutboxResilienceIT`: el outbox con Redis caído (eventos que no se pierden y salen en orden).
- `SeatLifecycleIT`: anular (US-151), cambiar de asiento (US-139, incluida la prueba de concurrencia: 100
  reservas distintas intentan pasarse al mismo asiento libre y exactamente una lo logra), bloquear (US-112,
  permisos por empresa, trigger y restricciones de V4) y cambiar el bus (US-119), con PostgreSQL y Redis reales.
- `OccupancyWaitlistIT`: ocupación con todos los estados y la tasa de abandono, otra empresa (404), el interno con
  varios viajes (US-191); y la lista de espera (US-140): anotarse con cupo (409), liberación que ofrece al
  primero que cabe, vencimiento que pasa al siguiente, conversión, cierre antes de la salida y al cancelar, y
  concurrencia (20 liberaciones simultáneas con 3 personas en la fila: nadie recibe el mismo asiento).
- `ObservabilityIT` (HT-10): el `traceparent` pasa del cambio de estado al outbox y al stream, el consumidor
  retoma la traza (con el consumidor encendido) y las métricas propias salen en `/actuator/prometheus`.
- `SegmentSalesIT` (US-141, US-142, HT-08): categorías desde el evento y el cambio de bus, tramos que se cruzan y
  que no, recorrido completo contra tramo, mapa y disponibilidad por tramo, confirmar, liberar, vencer, anular y
  cambiar por tramo, la restricción `EXCLUDE` rechazando un cruce aunque falle la aplicación, lista de espera por
  tramo, concurrencia (200 hilos por el mismo asiento: de los tramos que se cruzan gana exactamente uno y los que
  no se cruzan ganan todos) y el archivo con la ocupación de un viaje archivado.

Toda la plataforma: ver el README de **API-gateway**.

## Calidad

| Herramienta | Qué revisa | Dónde |
|---|---|---|
| JaCoCo | Cobertura de las pruebas y **mínimo obligatorio** (`jacoco:check`) | local: `target/site/jacoco/index.html` (tras `mvn verify`); CI: tabla en el resumen de cada ejecución y artifact `jacoco` |
| SonarQube Cloud | Bugs, code smells y cobertura; quality gate "Sonar way" (código nuevo ≥ 80 %) | proyecto `Trip-Bus_seat-inventory-service` de la organización `trip-bus` |
| Gitleaks | Secretos en todo el historial git | job `secretos` del CI |
| Trivy | Vulnerabilidades CRITICAL/HIGH con corrección disponible en la imagen Docker | job `docker` del CI |

### Cobertura mínima (JaCoCo)

`mvn verify` falla si la cobertura total baja de los mínimos del `pom.xml`, en **todas** las ramas
(también en `main`, donde Sonar no corre):

| Métrica | Medido (2026-11-05) | Mínimo |
|---|---|---|
| Líneas | 94,8 % | `jacoco.minimo.lineas` = 0.90 |
| Ramas | 83,6 % | `jacoco.minimo.ramas` = 0.80 |

**La cobertura nunca puede bajar: si agregas pruebas, sube el mínimo.** Corre `mvn verify`, mira
el total en `target/site/jacoco/index.html` (o la tabla del CI), redondéalo hacia abajo al 5 % y
actualiza la propiedad en el pom en el mismo PR. Las mejores candidatas para subir ramas son
`SeatInventoryService` y `EventConsumer`.

Fuera de la cobertura (mismas en `sonar.coverage.exclusions` y en los `<excludes>` de JaCoCo) solo
está lo que no tiene lógica: `SeatInventoryApplication`, los records sin métodos de `web/dto`, las
entidades JPA de solo constructor/getters (`SeatAuditLog`, `ProcessedEvent`). `config/OpenApiConfig` ya cuenta
(desde HT-10 tiene lógica: `OPENAPI_SERVER_URL`).
`Hold`, `SeatState`, `KnownItinerary` e `InventoryProperties` tienen lógica y **sí** cuentan.

### Vulnerabilidades aceptadas (`.trivyignore.yaml`)

Lista **temporal** de CVE que Trivy no debe tratar como error. Solo entran las que **no tienen
arreglo en Spring Boot 3.x** (hoy CVE-2026-47884 y CVE-2026-47890 de `spring-webmvc`, que solo se
corrigen en Spring Framework 7); cada una explica en `statement` por qué no aplica a este servicio.
Todas **vencen el 2027-01-31** (`expired_at`): desde esa fecha Trivy vuelve a fallar si no se migró a
Spring Boot 4.1. Lo que sí tiene parche nunca se agrega aquí: se corrige subiendo la versión
(ver las propiedades `*.version` de parches de seguridad en `pom.xml`). Los paquetes del sistema
(Alpine) se parchean con `apk upgrade` en la etapa `runtime` del `Dockerfile`; el CI reconstruye esa
etapa sin caché y con la imagen base recién descargada, así Trivy siempre ve los últimos parches.

El análisis de Sonar solo corre en `develop` (push y PR con base `develop`, ver
[Flujo de ramas y entornos](#flujo-de-ramas-y-entornos)) y si el repo tiene el secreto `SONAR_TOKEN`;
en otro caso el CI hace `mvn verify` y deja un aviso.

### Secretos del CI (`SONAR_TOKEN`, `DOCKERHUB_USERNAME`, `DOCKERHUB_TOKEN`)

| Secreto | Valor |
|---|---|
| `SONAR_TOKEN` | Token de análisis de SonarQube Cloud (organización `trip-bus`). Sin él, el CI omite Sonar con un aviso. |
| `DOCKERHUB_USERNAME` | Usuario de Docker Hub |
| `DOCKERHUB_TOKEN` | Personal access token con permiso **Public Repo Read-only** (solo lectura; nunca la contraseña) |

Los runners de GitHub comparten IP y agotan el límite de descargas anónimas de Docker Hub
(`toomanyrequests: You have reached your unauthenticated pull rate limit`), lo que rompe
Testcontainers (`postgres:16-alpine`, Redis) y el build de la imagen (`maven`, `eclipse-temurin`).
Con los secretos de Docker Hub los jobs `secretos`, `build-test` y `docker` inician sesión antes de
descargar nada y usan el límite de una cuenta autenticada. Gitleaks se descarga desde GHCR
(`ghcr.io/gitleaks/gitleaks`), que no tiene ese límite. Son **opcionales pero recomendados**: si
faltan, el CI descarga sin autenticar, deja un aviso y puede fallar por el límite. Se crean en
Settings → Secrets and variables → Actions.

Escaneos locales con Docker:

```bash
docker run --rm -v "$PWD:/repo" ghcr.io/gitleaks/gitleaks:v8.30.1 git /repo --redact
docker build -t seat-inventory-service:local . && \
  docker run --rm -v /var/run/docker.sock:/var/run/docker.sock -v "$PWD:/src" aquasec/trivy:0.70.0 \
  image --severity CRITICAL,HIGH --ignore-unfixed --ignorefile /src/.trivyignore.yaml seat-inventory-service:local
```
