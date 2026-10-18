# syntax=docker/dockerfile:1
# ==============================================================================
# Multi-stage Dockerfile for Clone-Yourself RAG (Spring Boot 3.5 + Lucene 10, JDK 25)
# ==============================================================================

# ------------------------------------------------------------------------------
# Stage 1: Build & Model Preparation Stage
# ------------------------------------------------------------------------------
FROM eclipse-temurin:25-jdk AS builder
WORKDIR /build

# Install build utilities: Maven, curl (for model download), ca-certificates
RUN apt-get update && \
    apt-get install -y --no-install-recommends maven curl ca-certificates && \
    rm -rf /var/lib/apt/lists/*

# Cache Maven dependencies separately from sources to optimize rebuild times
COPY pom.xml .
RUN mvn dependency:resolve dependency:resolve-plugins -B

# Model Asset Route:
# Model files enter via this build stage. We execute scripts/fetch-model.sh to download
# and verify the pinned BGE-small-zh-v1.5 ONNX model and vocabulary via SHA256.
# By keeping models/ in .dockerignore, we avoid transferring 95MB+ over the Docker daemon context.
# If building in a restricted offline environment where models/ already exists locally,
# uncomment the line below and remove 'models/' from .dockerignore:
# COPY models/ models/
COPY scripts/fetch-model.sh scripts/fetch-model.sh
RUN chmod +x scripts/fetch-model.sh && ./scripts/fetch-model.sh models

# Copy source code and build application jar
# Note: Unit tests are verified during CI/development; skipTests here builds the packaged artifact.
COPY src src
RUN mvn package -DskipTests -B

# ------------------------------------------------------------------------------
# Stage 2: Production Runtime Stage
# ------------------------------------------------------------------------------
# eclipse-temurin:25-jre provides a slim, glibc-based JRE environment.
# glibc is strictly required by Microsoft ONNX Runtime native bindings (libonnxruntime.so).
FROM eclipse-temurin:25-jre AS runner

WORKDIR /app

# Install curl for health check probe in container environments
RUN apt-get update && \
    apt-get install -y --no-install-recommends curl && \
    rm -rf /var/lib/apt/lists/*

# Create dedicated non-root user and group
RUN groupadd -r appgroup && useradd -r -g appgroup -d /app -s /usr/sbin/nologin appuser

# Pre-create app directories with appropriate permissions
RUN mkdir -p /app/data /app/models && \
    chown -R appuser:appgroup /app

# Copy model assets prepared at build time (running container performs ZERO downloads)
COPY --from=builder --chown=appuser:appgroup /build/models /app/models

# Copy built Spring Boot fat JAR
COPY --from=builder --chown=appuser:appgroup /build/target/digital-twin-0.1.0.jar /app/app.jar

# Copy third-party model license and attribution notice
COPY --chown=appuser:appgroup NOTICE /app/NOTICE

# Configure runtime environment:
# 1. SERVER_ADDRESS=0.0.0.0 binds embedded Tomcat to all container network interfaces
# 2. --enable-native-access=ALL-UNNAMED enables foreign native memory access for ONNX Runtime on JDK 25
# 3. DATA_DIR points to mounted index persistence directory
# 4. MODEL_DIR points to bundled offline ONNX assets
ENV SERVER_ADDRESS=0.0.0.0 \
    PORT=8080 \
    DATA_DIR=/app/data \
    MODEL_DIR=/app/models \
    JAVA_TOOL_OPTIONS="--enable-native-access=ALL-UNNAMED"

# Switch to non-root user
USER appuser

EXPOSE 8080

# Run Spring Boot application
ENTRYPOINT ["java", "--enable-native-access=ALL-UNNAMED", "-jar", "/app/app.jar"]
