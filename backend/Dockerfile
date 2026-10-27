# ==============================================================================
# QualiScan Backend Multi-Stage Dockerfile
# Stage 1: Build the Spring Boot application using Maven wrapper and JDK 17
# Stage 2: Minimal, secure JRE 17 runtime image running as a non-root user
# ==============================================================================

# -----------------------------
# Stage 1: Build
# -----------------------------
FROM eclipse-temurin:17-jdk-jammy AS builder

WORKDIR /build

# Copy Maven wrapper and POM first for layer dependency caching
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./

# Ensure wrapper script is executable and resolve dependencies
RUN chmod +x mvnw && (./mvnw dependency:go-offline -B || true)

# Copy application source code and compile production JAR
COPY src/ src/
RUN ./mvnw clean package -DskipTests -B

# -----------------------------
# Stage 2: Runtime
# -----------------------------
FROM eclipse-temurin:17-jre-jammy AS runner

WORKDIR /app

# Create a dedicated non-root system group and user
RUN groupadd -r qualiscan && \
    useradd -r -g qualiscan -u 1001 -s /sbin/nologin qualiscan && \
    mkdir -p /app/data /app/uploads/snapshots && \
    chown -R qualiscan:qualiscan /app

# Copy the built jar from the builder stage
COPY --from=builder --chown=qualiscan:qualiscan /build/target/*.jar /app/app.jar

# Run as non-root user for security
USER qualiscan

# Default environment configuration
ENV PORT=8080 \
    SERVER_PORT=8080 \
    SPRING_PROFILES_ACTIVE=postgres \
    UPLOAD_DIR=/app/uploads/snapshots

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
