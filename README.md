# Seat Inventory Service

[![Quality Gate Status](https://sonarcloud.io/api/project_badges/measure?project=Trip-Bus_seat-inventory-service&metric=alert_status)](https://sonarcloud.io/summary/new_code?id=Trip-Bus_seat-inventory-service)

El corazón de la concurrencia de TripBus: estado de cada asiento, retenciones con TTL, bloqueos
distribuidos y confirmación de venta. **Ningún asiento se vende dos veces.**

**Stack:** Java 21 · Spring Boot 3.5.16 · PostgreSQL 16 · Redis 7

**HU:** US-016, US-017, US-018, US-022, US-025, US-026, US-080 · **Puerto:** 18003 · **BD:** `tripbus_inventory` (puerto QA 18103)

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

Los `GET` de `/seats/itineraries/**` y `/seats/availability` son públicos; el resto exige JWT de identity.

API interna (`X-Internal-Token`): `POST /internal/holds`, `GET /internal/holds/{id}`, `POST /internal/holds/{id}/confirm` (ConfirmSale), `POST /internal/holds/{id}/release`, `POST /internal/offers/{offerId}/seats`, `POST /internal/offers/{offerId}/release`.

**Publica:** `SeatHeld`, `SeatReleased`, `SeatSold`, `HoldExpired`, `SeatStateChanged`.
**Consume:** `ItineraryPublished` (crea los asientos), `ItineraryCancelled`, `PaymentFailed`, `OfferClosed`.

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
`REDIS_PORT` (18100), `HOLD_TTL` (10m). `DB_HOST` reemplaza a la antigua `DB_URL`.

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
  salientes (a catalog, `CatalogClient`), así una petición se sigue entre servicios. Logs legibles con
  `[requestId]` en IDE/QA y JSON (con `requestId` y `"service":"seat-inventory-service"`) en PROD.

## Pruebas

```bash
mvn -B verify      # unitarias + integración con PostgreSQL y Redis reales (Testcontainers: Docker abierto)
```

- `*Test` / `*Tests`: unitarias.
- `SeatConcurrencyIT`: levanta PostgreSQL 16 y Redis 7 con Testcontainers, corre las migraciones Flyway
  reales (incluido el trigger contra la doble venta) y prueba US-080, confirmaciones concurrentes,
  extensión y expiración de retenciones y asientos en oferta.

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

| Métrica | Medido (2026-10-09) | Mínimo |
|---|---|---|
| Líneas | 63,9 % | `jacoco.minimo.lineas` = 0.60 |
| Ramas | 47,2 % | `jacoco.minimo.ramas` = 0.45 |

**La cobertura nunca puede bajar: si agregas pruebas, sube el mínimo.** Corre `mvn verify`, mira
el total en `target/site/jacoco/index.html` (o la tabla del CI), redondéalo hacia abajo al 5 % y
actualiza la propiedad en el pom en el mismo PR. Ramas está por debajo del 60 % de referencia:
las mejores candidatas para subirla son `SeatInventoryService` (63 ramas sin cubrir), `EventConsumer`,
`CurrentUser`, `InventoryEventsHandler` y los controladores `SeatController` / `InternalController`.

Fuera de la cobertura (mismas en `sonar.coverage.exclusions` y en los `<excludes>` de JaCoCo) solo
está lo que no tiene lógica: `SeatInventoryApplication`, los records sin métodos de `web/dto`, las
entidades JPA de solo constructor/getters (`SeatAuditLog`, `ProcessedEvent`) y `config/OpenApiConfig`.
`Hold`, `SeatState`, `KnownItinerary` e `InventoryProperties` tienen lógica y **sí** cuentan.

### Vulnerabilidades aceptadas (`.trivyignore.yaml`)

Lista **temporal** de CVE que Trivy no debe tratar como error. Solo entran las que **no tienen
arreglo en Spring Boot 3.x** (hoy CVE-2026-47884 y CVE-2026-47890 de `spring-webmvc`, que solo se
corrigen en Spring Framework 7); cada una explica en `statement` por qué no aplica a este servicio.
Todas **vencen el 2027-01-31** (`expired_at`): desde esa fecha Trivy vuelve a fallar si no se migró a
Spring Boot 4.1. Lo que sí tiene parche nunca se agrega aquí: se corrige subiendo la versión
(ver las propiedades `*.version` de parches de seguridad en `pom.xml`).

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
