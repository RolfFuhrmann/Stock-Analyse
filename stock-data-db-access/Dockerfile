# ── Build Stage ──────────────────────────────────────────────────────────────
FROM --platform=linux/arm64 eclipse-temurin:25-jdk-alpine AS build

WORKDIR /app

# Abhängigkeiten zuerst cachen (Layer-Optimierung)
COPY pom.xml .
COPY .mvn/ .mvn/
COPY mvnw .

RUN chmod +x mvnw && ./mvnw dependency:go-offline -q

COPY src ./src

RUN ./mvnw package -DskipTests -q

# ── Runtime Stage ─────────────────────────────────────────────────────────────
FROM --platform=linux/arm64 eclipse-temurin:25-jre-alpine

WORKDIR /app

# tzdata: Alpine-Images enthalten standardmäßig KEINE Zeitzonen-Datenbank.
# Ohne dieses Paket fällt die JVM auf UTC zurück, selbst wenn TZ gesetzt ist
# (betrifft Log-Zeitstempel und alle LocalDateTime.now()-Aufrufe im Code).
RUN apk add --no-cache tzdata
ENV TZ=Europe/Berlin

# Nicht als root laufen
RUN addgroup -S appgroup && adduser -S appuser -G appgroup
USER appuser

COPY --from=build /app/target/*.jar app.jar

EXPOSE 8013

ENTRYPOINT ["java", "--enable-native-access=ALL-UNNAMED", "-jar", "app.jar"]
