# One image recipe for every Spring Boot module. k8s/deploy.mjs builds the jars, then one image per module:
#   docker build --build-arg MODULE=dc-service -t branch-dc/dc-service:<tag> .
# MODULE: dc-service, branch-service or branch-listener.
FROM eclipse-temurin:21-jre

ARG MODULE
WORKDIR /app
COPY ${MODULE}/target/${MODULE}-0.0.1-SNAPSHOT.jar app.jar

# Not root inside the container.
RUN useradd --system --uid 10001 app
USER app

# Heap sized from the container's memory limit (the pod's limits.memory), not a fixed -Xmx: 55% for the heap,
# the rest for metaspace, threads and the JIT, so the pod stays under its limit. Out of memory: exit at once, so
# Kubernetes restarts the pod (instead of a JVM limping on). Serial GC: the smallest footprint for small heaps.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=55 -XX:InitialRAMPercentage=15 -XX:+UseSerialGC -XX:+ExitOnOutOfMemoryError"
ENTRYPOINT ["java", "-jar", "app.jar"]
