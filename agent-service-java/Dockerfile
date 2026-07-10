# ── Build-Stage ──────────────────────────────────────────────
FROM --platform=linux/arm64 maven:3.9-eclipse-temurin-21-alpine AS build
WORKDIR /app

COPY pom.xml .
RUN mvn dependency:go-offline -q

COPY src ./src
RUN mvn package -DskipTests -q

# ── Runtime-Stage ────────────────────────────────────────────
FROM --platform=linux/arm64 eclipse-temurin:21-jre-alpine
WORKDIR /app

RUN addgroup -S appgroup && adduser -S appuser -G appgroup
COPY --from=build /app/target/*.jar app.jar
USER appuser

EXPOSE 8016
ENTRYPOINT ["java", "-jar", "app.jar"]

