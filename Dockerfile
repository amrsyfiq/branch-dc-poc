# One image recipe for every Spring Boot module. Build the jars first, then the images:
#   ./mvnw -DskipTests package
#   docker compose build
# docker-compose.yml passes MODULE (dc-service, branch-service, branch-listener).
FROM eclipse-temurin:21-jre

ARG MODULE
WORKDIR /app
COPY ${MODULE}/target/${MODULE}-0.0.1-SNAPSHOT.jar app.jar

# Not root inside the container.
RUN useradd --system --uid 10001 app
USER app

# Small heaps: five JVMs share Docker's memory with two WSO2 servers (Phase 7 sizes them from the pod's memory limit).
ENV JAVA_TOOL_OPTIONS="-Xms64m -Xmx256m -XX:+UseSerialGC"
ENTRYPOINT ["java", "-jar", "app.jar"]
