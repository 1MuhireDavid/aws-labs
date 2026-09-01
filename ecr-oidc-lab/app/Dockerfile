# syntax=docker/dockerfile:1

# ---------------------------------------------------------------------------
# Stage 1: build the jar with Maven
#   - Never shipped; only target/app.jar is copied out of this stage.
# ---------------------------------------------------------------------------
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

# Copy only the POM first so this (slow) dependency-resolution layer is
# cached unless pom.xml itself changes.
COPY pom.xml .
RUN mvn -B dependency:go-offline

# Copy application source after deps are resolved (better layer caching).
COPY src ./src
RUN mvn -B clean package -DskipTests

# ---------------------------------------------------------------------------
# Stage 2: minimal runtime image
#   - Only the built jar is copied in; no Maven, no source, no build cache.
# ---------------------------------------------------------------------------
FROM eclipse-temurin:21-jre-alpine

WORKDIR /app

# Create a dedicated, unprivileged, non-login user/group instead of running
# as root.
RUN addgroup -S app && adduser -S app -G app

COPY --from=build /build/target/app.jar ./app.jar
RUN chown app:app ./app.jar

# The base JRE image ships Alpine's package manager even though nothing here
# ever needs it at runtime -- drop it to shrink the attack surface, same
# rationale as stripping npm/npx/corepack from the old Node.js image.
RUN rm -rf /sbin/apk /etc/apk /lib/apk /usr/share/apk /var/cache/apk

# Drop root privileges for the remainder of the build and at runtime.
USER app

EXPOSE 8080

# Let the orchestrator / Docker know the container is actually serving traffic.
HEALTHCHECK --interval=30s --timeout=3s --start-period=5s --retries=3 \
  CMD wget -qO- http://localhost:8080/health || exit 1

# Exec form: java runs as PID 1, receives signals directly (clean shutdowns).
# -XX:+UseContainerSupport + MaxRAMPercentage keep the JVM's heap sized to
# the container's actual memory limit instead of the host's.
ENTRYPOINT ["java", "-XX:+UseContainerSupport", "-XX:MaxRAMPercentage=75.0", "-jar", "app.jar"]
