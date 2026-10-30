# Stage 1: Build and extract layered Spring Boot archive
FROM public.ecr.aws/docker/library/eclipse-temurin:21-jdk-alpine AS builder
WORKDIR /workspace

COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN sed -i 's/\r$//' mvnw && chmod +x mvnw && ./mvnw dependency:go-offline -B

COPY src/main/ src/main/
RUN ./mvnw package -DskipTests -B && \
    java -Djarmode=tools -jar target/*.jar extract --layers --launcher --destination extracted

# Stage 2: Minimal non-root Java 21 runtime image
FROM public.ecr.aws/docker/library/eclipse-temurin:21-jre-alpine AS runtime
WORKDIR /app

RUN addgroup -S scheduler && adduser -S scheduler -G scheduler

COPY --from=builder --chown=scheduler:scheduler /workspace/extracted/dependencies/ ./
COPY --from=builder --chown=scheduler:scheduler /workspace/extracted/spring-boot-loader/ ./
COPY --from=builder --chown=scheduler:scheduler /workspace/extracted/snapshot-dependencies/ ./
COPY --from=builder --chown=scheduler:scheduler /workspace/extracted/application/ ./

USER scheduler
EXPOSE 8080

HEALTHCHECK --interval=10s --timeout=3s --start-period=25s --retries=6 \
    CMD wget -qO- http://localhost:8080/actuator/health/readiness || exit 1

ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-XX:+UseZGC", "-XX:+ZGenerational", "org.springframework.boot.loader.launch.JarLauncher"]
