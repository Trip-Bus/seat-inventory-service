# ---- Etapa 1: compilación (Maven + JDK 21) ----
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src ./src
RUN mvn -B -q package -DskipTests

# ---- Etapa 2: imagen de ejecución (solo JRE) ----
FROM eclipse-temurin:21-jre-alpine AS runtime
# Parches de seguridad de Alpine publicados después de la imagen base (p. ej. brotli-libs,
# CVE-2026-111497): Trivy falla con cualquier CRITICAL/HIGH que ya tenga arreglo. El CI reconstruye
# esta etapa sin caché (no-cache-filters: runtime) para que siempre tome los últimos parches.
RUN apk upgrade --no-cache \
    && addgroup -S tripbus && adduser -S tripbus -G tripbus
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar
USER tripbus
ARG GIT_COMMIT=local
ENV SERVER_PORT=18003 APP_COMMIT=${GIT_COMMIT}
EXPOSE 18003
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+UseSerialGC"
HEALTHCHECK --interval=10s --timeout=3s --start-period=120s --retries=6 \
  CMD wget -qO- http://localhost:${SERVER_PORT}/actuator/health/readiness || exit 1
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
