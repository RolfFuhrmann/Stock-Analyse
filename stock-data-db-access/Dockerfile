# ── Build Stage ──────────────────────────────────────────────────────────────
FROM --platform=linux/arm64 eclipse-temurin:21-jdk-alpine AS build

WORKDIR /app

# Abhängigkeiten zuerst cachen (Layer-Optimierung)
COPY pom.xml .
COPY .mvn/ .mvn/
COPY mvnw .

RUN chmod +x mvnw && ./mvnw dependency:go-offline -q

COPY src ./src

RUN ./mvnw package -DskipTests -q

# ── Runtime Stage ─────────────────────────────────────────────────────────────
FROM --platform=linux/arm64 eclipse-temurin:21-jre-alpine

WORKDIR /app

# Nicht als root laufen
RUN addgroup -S appgroup && adduser -S appuser -G appgroup
USER appuser

COPY --from=build /app/target/*.jar app.jar

EXPOSE 8013

ENTRYPOINT ["java", "-jar", "app.jar"]
