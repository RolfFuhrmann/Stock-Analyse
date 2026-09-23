# ── Build-Stage ──────────────────────────────────────────────
FROM --platform=linux/arm64 maven:3.9-eclipse-temurin-25-alpine AS build
WORKDIR /app

COPY pom.xml .
RUN mvn dependency:go-offline -q

COPY src ./src
RUN mvn package -DskipTests -q

# ── Runtime-Stage ────────────────────────────────────────────
FROM --platform=linux/arm64 eclipse-temurin:25-jre-alpine
WORKDIR /app

# tzdata: Alpine-Images enthalten standardmäßig KEINE Zeitzonen-Datenbank.
# Ohne dieses Paket fällt die JVM auf UTC zurück, selbst wenn TZ gesetzt ist
# (gleiches Problem wie zuvor bei stock-data-db-access).
RUN apk add --no-cache tzdata
ENV TZ=Europe/Berlin

RUN addgroup -S appgroup && adduser -S appuser -G appgroup
COPY --from=build /app/target/*.jar app.jar
USER appuser

EXPOSE 8016
ENTRYPOINT ["java", "--enable-native-access=ALL-UNNAMED", "-jar", "app.jar"]
