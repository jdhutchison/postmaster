# Postmaster container image.
#
#   docker build -t postmaster .                 # native executable (the default)
#   docker build -t postmaster --target jvm .    # JVM build: quicker, needs far less memory
#
# The native build compiles for the architecture Docker builds for, and needs about 6 GB of
# memory for the builder. Production (arm64) images are built on tigerbear itself.
#
# Mounts (see docs/deployment.md):
#   /config/postmaster.yml   the startup configuration; its path is the only argument
#   /config/khal             the khal configuration (postmaster.khal-config points into it)
#   the calendars directory  wherever khal.ini's calendar paths say, e.g. /data/calendars
#   /data/attachments        downloaded attachments (the attachments.path setting)
#
# Build arguments POSTMASTER_UID and CALENDARS_GID let the container's user match the host's
# owner of the attachments and the calendars group, so both stay writable.

ARG GRAALVM_IMAGE=ghcr.io/graalvm/native-image-community:25
ARG JRE_IMAGE=eclipse-temurin:25-jre
ARG RUNTIME_IMAGE=debian:trixie-slim

# ---------------------------------------------------------------------------------------------
# Build
# ---------------------------------------------------------------------------------------------

FROM ${JRE_IMAGE} AS jre

FROM ${GRAALVM_IMAGE} AS build
WORKDIR /src
# Gradle and the dependencies first, so they're cached while only the sources change.
# version.properties is the version this build gets (bin/build-image raises it on the host
# afterwards); build.gradle.kts reads it, so it's needed before Gradle first runs.
COPY gradlew settings.gradle.kts build.gradle.kts gradle.properties version.properties ./
COPY gradle gradle
RUN ./gradlew --no-daemon --console=plain dependencies > /dev/null
COPY src src

# GIT_COMMIT is the commit it's built from, for the status endpoint (there's no .git here). A
# build argument only reaches the stage that declares it, so each build stage does.

FROM build AS build-jvm
ARG GIT_COMMIT=
RUN ./gradlew --no-daemon --console=plain quarkusBuild -PkeepVersion "-PgitCommit=${GIT_COMMIT}"

FROM build AS build-native
ARG GIT_COMMIT=
RUN ./gradlew --no-daemon --console=plain quarkusBuild -PkeepVersion "-PgitCommit=${GIT_COMMIT}" \
        -Dquarkus.native.enabled=true \
        -Dquarkus.package.jar.enabled=false \
    && cp build/*-runner /src/postmaster

# ---------------------------------------------------------------------------------------------
# Runtime: khal, curl and headless Chromium, run as postmaster in the calendars group
# ---------------------------------------------------------------------------------------------

FROM ${RUNTIME_IMAGE} AS runtime
ARG POSTMASTER_UID=1000
ARG CALENDARS_GID=1000
RUN apt-get update \
    && apt-get install -y --no-install-recommends ca-certificates curl khal chromium fonts-liberation tzdata \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --gid "${CALENDARS_GID}" calendars \
    && useradd --uid "${POSTMASTER_UID}" --gid calendars --create-home --shell /usr/sbin/nologin postmaster \
    && mkdir -p /config/khal /data/attachments /data/calendars /data/todo /app \
        /home/postmaster/.config/khal /home/postmaster/.cache/khal \
    && chown postmaster:calendars /data/attachments /data/calendars /data/todo \
        /home/postmaster/.config /home/postmaster/.config/khal /home/postmaster/.cache /home/postmaster/.cache/khal
ENV HOME=/home/postmaster \
    TZ=Australia/Melbourne
# So the deployment can prune superseded images
LABEL sh.hutch.service=postmaster
WORKDIR /app
EXPOSE 8080
VOLUME ["/config", "/data/attachments", "/data/calendars", "/data/todo"]
# Unhealthy while the status endpoint says DOWN (the database or Dovecot is unavailable);
# DEGRADED still counts as healthy (CLARIFICATIONS.md R7)
HEALTHCHECK --interval=60s --timeout=10s --start-period=60s --retries=3 \
    CMD curl -fsS -o /dev/null http://localhost:8080/mail/health || exit 1

FROM runtime AS jvm
ENV JAVA_HOME=/opt/java/openjdk
COPY --from=jre /opt/java/openjdk /opt/java/openjdk
COPY --from=build-jvm --chown=postmaster:calendars /src/build/quarkus-app /app/
USER postmaster
ENTRYPOINT ["/opt/java/openjdk/bin/java", "-XX:MaxRAMPercentage=75", "-jar", "/app/quarkus-run.jar"]
CMD ["/config/postmaster.yml"]

FROM runtime AS native
COPY --from=build-native --chown=postmaster:calendars /src/postmaster /app/postmaster
USER postmaster
ENTRYPOINT ["/app/postmaster"]
CMD ["/config/postmaster.yml"]
