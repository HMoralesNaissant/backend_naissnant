# syntax=docker/dockerfile:1.7
# =============================================================================
# TenantOS registrar — multi-stage build
# =============================================================================
# Layers are ordered by how often they change: base image, then Gradle wrapper
# and build files, then source. Editing a source file does not re-download
# dependencies.
#
# The final image contains only a JRE and the layered Spring Boot jar — no JDK,
# no Gradle, no source.
# =============================================================================

ARG JDK_VERSION=21-jdk-alpine
ARG JRE_VERSION=21-jre-alpine

# -----------------------------------------------------------------------------
# build — full toolchain, produces the executable jar
# -----------------------------------------------------------------------------
FROM eclipse-temurin:${JDK_VERSION} AS build
WORKDIR /workspace

COPY gradlew settings.gradle build.gradle ./
COPY gradle ./gradle
RUN chmod +x gradlew
# Resolve dependencies in their own layer so they are cached until build files change.
RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew --no-daemon dependencies > /dev/null

COPY src ./src
RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew --no-daemon bootJar -x test && \
    # Split into layers (dependencies / snapshot-dependencies / application) so a code-only
    # change pushes a few KB instead of the whole fat jar.
    # Normalise the name (excluding the `-plain` jar) so the runner does not depend on the version.
    cp "$(find build/libs -name '*.jar' ! -name '*-plain.jar' | head -n1)" app.jar && \
    java -Djarmode=tools -jar app.jar extract --layers --destination /workspace/extracted

# -----------------------------------------------------------------------------
# runner — what actually ships
# -----------------------------------------------------------------------------
FROM eclipse-temurin:${JRE_VERSION} AS runner
WORKDIR /app

# tini as PID 1 so SIGTERM reaches the JVM and zombies get reaped; curl for HEALTHCHECK.
RUN apk add --no-cache tini curl && \
    addgroup -S -g 1001 app && adduser -S -u 1001 -G app app

ENV PORT=8080
    # Size the heap from the container's memory limit, not the host's.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"

COPY --chown=app:app --from=build /workspace/extracted/dependencies/ ./
COPY --chown=app:app --from=build /workspace/extracted/snapshot-dependencies/ ./
COPY --chown=app:app --from=build /workspace/extracted/application/ ./

USER app
EXPOSE 8080

# Compose and standalone Docker use this; Kubernetes uses the probes in the manifest.
HEALTHCHECK --interval=30s --timeout=3s --start-period=60s --retries=3 \
    CMD curl -fsS http://127.0.0.1:${PORT}/actuator/health || exit 1

ENTRYPOINT ["/sbin/tini", "--"]
# Boot 3.3+ has no JarLauncher in the layers: the app jar references ./lib/ via its manifest.
CMD ["java", "-jar", "app.jar"]
