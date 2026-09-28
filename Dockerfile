# ===========================================================================
#  Aran — Dockerfile
#  Multi-stage build: Gradle build image → minimal JRE runtime image.
#
#  Stages:
#    gradle-scripts — gathers every build script for the dependency-cache layer
#    builder        — builds the fat jar with the Gradle wrapper
#    frontend       — builds the SPA when WITH_UI=true
#    runtime        — Eclipse Temurin JRE 25 image (no JDK; bash, no wget or curl)
#
#  Usage:
#    docker build -t aran:dev .
#    docker run --env-file .env.docker -p 8081:8081 aran:dev
#
#  The image runs as a non-root user (uid 1000) by default. Set
#  JAVA_OPTS for JVM tuning; ARAN_* env vars for configuration.
# ===========================================================================

# ---- Stage 0: the Gradle build scripts, and nothing else ------------------
# The dependency-cache layer below needs every module's build.gradle (Gradle 9 refuses to
# configure an included project whose directory is missing) and none of its sources, or any
# source edit would invalidate it. This was a hand-kept COPY list; it silently dropped
# aran-adapter-iceberg when that module landed (S15-1). Deriving the set from the context means
# a new module needs no edit here. The `COPY . .` below does not bust the builder's cache: a
# `COPY --from` is keyed on the content of what it copies, so only a build-script edit moves it.
FROM eclipse-temurin:25-jdk AS gradle-scripts
WORKDIR /src
COPY . .
RUN mkdir /scripts \
 && find . \( -name build.gradle -o -name settings.gradle -o -name gradle.properties \) -print \
    | xargs cp --parents -t /scripts

# ---- Stage 1: builder ----------------------------------------------------
# Use the same Java version the toolchain targets. The Gradle wrapper
# downloads Gradle itself; only the JDK needs to be present.
FROM eclipse-temurin:25-jdk AS builder

WORKDIR /workspace

# Copy Gradle wrapper first — Docker layer cache keeps this stable across
# source changes if the wrapper version doesn't change.
COPY gradlew gradlew.bat ./
COPY gradle/ gradle/

# Build scripts before source, so dependency resolution is cached independently of source
# changes. No `|| true`: it used to hide exactly the failure above, and a Docker build has no
# Gradle cache to fall back on, so a resolution failure here would fail the bootJar step anyway.
# GIT_COMMIT is set inline, not from the ARG below: configuring aran-application evaluates its
# buildInfo commit eagerly, which runs a VCS binary this image lacks unless the variable is set,
# and an ARG this early would re-key the layer on every commit.
COPY --from=gradle-scripts /scripts/ ./
RUN GIT_COMMIT=unknown ./gradlew resolveDependencies --no-daemon -q

# Now copy the full source tree.
COPY . .

# Build the executable jar. Skip tests here — tests run in CI before the
# Docker build step. GIT_COMMIT is passed in because this stage has neither git
# nor .git — bootBuildInfo's exec provider cannot run here (see build.gradle).
ARG GIT_COMMIT=unknown
ENV GIT_COMMIT=${GIT_COMMIT}
RUN ./gradlew :aran-application:bootJar --no-daemon -x test

# ---- Stage 1.5: frontend (optional) --------------------------------------
# Builds the SPA into the image so the standalone profile serves API and UI from
# one origin — which is also what makes reverse-proxying Keycloak under /auth
# worthwhile: one host, no port to explain.
#
# Off by default (WITH_UI=false) because the cloud deployment serves the SPA
# separately, and an unconditional npm ci would put Node in the critical path of
# every backend image build.
FROM node:22-slim AS frontend
ARG WITH_UI=false
WORKDIR /web
COPY aran-web/package*.json ./
RUN if [ "$WITH_UI" = "true" ]; then npm ci; else mkdir -p node_modules; fi
COPY aran-web/ ./
RUN if [ "$WITH_UI" = "true" ]; then npm run build; else mkdir -p dist; fi

# ---- Stage 2: runtime ----------------------------------------------------
FROM eclipse-temurin:25-jre AS runtime

# Principle of least privilege: run as a non-root user.
# The base image has an existing 'ubuntu' user with UID 1000. We delete it first.
RUN userdel -r ubuntu || true
# --home is load-bearing: without it adduser gives the user HOME=/nonexistent, and DuckDB installs
# every extension a federated read needs (postgres, mysql, httpfs, iceberg) under ~/.duckdb. Each
# INSTALL then failed with "Can't find the home directory", so no federated source could be read
# in this image. ProductImageRuntimeUserTest runs the install as this user.
RUN addgroup --system aran && adduser --system --home /home/aran --ingroup aran --uid 1000 aran
# aran.jdbc.driver-cache-dir's default. The process cannot create it once it is not root, and
# without it every relational source whose driver is downloaded (JdbcBootstrap, S4-4) fails: V69's
# first standalone boot logged "Failed to create driver cache directory at /var/aran/drivers".
RUN mkdir -p /var/aran/drivers && chown -R aran:aran /var/aran
USER aran
ENV HOME=/home/aran

WORKDIR /app

# Copy only the fat jar from the builder stage.
COPY --from=builder /workspace/aran-application/build/libs/*.jar app.jar

# The built SPA, or an empty directory when WITH_UI=false. Copied unconditionally
# because a COPY cannot be made conditional; the standalone profile points
# spring.web.resources.static-locations at it, and every other profile ignores it.
COPY --from=frontend /web/dist/ /app/static/

# Self-hosted license key path (optional). Mount a PEM file here when
# running with aran.licensing.mode=signed-token.
VOLUME /etc/aran

EXPOSE 8081

# Default JVM flags for container-aware memory limits and G1GC.
# Override JAVA_OPTS at runtime for tuning.
ENV JAVA_OPTS="-XX:+UseG1GC \
               -XX:MaxRAMPercentage=75.0 \
               -XX:InitialRAMPercentage=50.0 \
               -Djava.security.egd=file:/dev/./urandom"

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]

# Actuator is served on the management port (8082 by default, ARAN_MANAGEMENT_PORT),
# not on the API port — application.yml states the API port serves no /actuator paths.
# Polling 8081 here made every `docker run` of this image report unhealthy forever;
# it went unnoticed because docker-compose overrides the healthcheck with the right port.
# The probe is bash's /dev/tcp, not wget or curl: this JRE image ships neither, so the wget
# probe that stood here could not run and every container reported unhealthy regardless —
# compose.standalone.yml sets no override, so its aran-app was one of them.
HEALTHCHECK --interval=15s --timeout=5s --start-period=60s --retries=5 \
  CMD bash -c 'exec 3<>/dev/tcp/127.0.0.1/"${ARAN_MANAGEMENT_PORT:-8082}" \
    && printf "GET /actuator/health/readiness HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n" >&3 \
    && grep -q "\"status\":\"UP\"" <&3'

