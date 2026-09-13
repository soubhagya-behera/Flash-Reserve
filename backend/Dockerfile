# syntax=docker/dockerfile:1

# ── Stage 1: Build ──
FROM maven:3.9-eclipse-temurin-17 AS builder
WORKDIR /app

# Leverage layer caching: copy pom first and pre-fetch dependencies
COPY pom.xml .
RUN mvn -B dependency:go-offline

COPY src ./src

# Build the Spring Boot JAR. The pom excludes src/main/resources/application.properties
# from the JAR and copies application-example.properties as application.properties,
# so no local secrets are ever baked into the image. Use -DskipTests for image
# builds; tests are run separately in CI/local verification.
RUN mvn -B package -DskipTests

# ── Stage 2: Runtime ── (minimal JRE, no JDK/build tools)
FROM eclipse-temurin:17-jre-jammy AS runtime
WORKDIR /app

# Create non-root user for production
RUN groupadd -r appgroup && useradd -r -g appgroup appuser

COPY --from=builder /app/target/flashreserve-*.jar /app/app.jar

# Render injects PORT at runtime; EXPOSE is documentation only
EXPOSE 8080

USER appuser

# Respect Render's PORT via server.port=${PORT:8080} (see application-example.properties).
# SPRING_PROFILES_ACTIVE=production must be set in Render to enable JWT hardening
# and disable Swagger/OpenAPI.
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
