# ===================== BUILD STAGE =====================
FROM maven:3.9.10-eclipse-temurin-17-alpine AS builder
WORKDIR /app

# Copy Maven wrapper and POM first (layer caching for deps)
COPY pom.xml .
RUN mvn dependency:go-offline -q

# Copy source and build
COPY src ./src
RUN mvn package -DskipTests -q

# Extract layered jar for optimal Docker layer caching
RUN java -Djarmode=layertools -jar target/*.jar extract --destination target/extracted

# ===================== RUNTIME STAGE =====================
FROM eclipse-temurin:17-jre-alpine AS runtime
WORKDIR /app

# Non-root user for security
RUN addgroup -S appgroup && adduser -S appuser -G appgroup
USER appuser

# Copy layered jar (dependencies first → most cache-stable layers first)
COPY --from=builder /app/target/extracted/dependencies/          ./
COPY --from=builder /app/target/extracted/spring-boot-loader/    ./
COPY --from=builder /app/target/extracted/snapshot-dependencies/ ./
COPY --from=builder /app/target/extracted/application/           ./

# JVM tuning for containers
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0 \
               -XX:+UseContainerSupport \
               -XX:+UseG1GC \
               -XX:MaxGCPauseMillis=200 \
               -Djava.security.egd=file:/dev/./urandom"

EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=10s --start-period=60s --retries=3 \
  CMD wget -qO- http://localhost:8080/actuator/health || exit 1

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS org.springframework.boot.loader.launch.JarLauncher"]
