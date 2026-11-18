# syntax=docker/dockerfile:1.7
FROM maven:3.10.0-eclipse-temurin-21-noble@sha256:f6d6c7eb62ca6a7034c17bb04a6177a8269ca38e3a1febf95094dfcd97d1305b AS build
WORKDIR /build
ARG MODULE

# Every reactor POM is needed even when only one service is selected.
COPY pom.xml ./pom.xml
COPY incident-service/pom.xml incident-service/pom.xml
COPY event-service/pom.xml event-service/pom.xml
COPY service-simulator/pom.xml service-simulator/pom.xml
COPY diagnostics-mcp-server/pom.xml diagnostics-mcp-server/pom.xml
COPY ai-analysis-service/pom.xml ai-analysis-service/pom.xml
COPY api-gateway/pom.xml api-gateway/pom.xml
RUN case "$MODULE" in \
      incident-service|event-service|service-simulator|diagnostics-mcp-server|ai-analysis-service|api-gateway) ;; \
      *) echo 'MODULE must name one of the six application modules' >&2; exit 1 ;; \
    esac
RUN --mount=type=cache,target=/root/.m2,sharing=locked \
    mvn -B -ntp -pl "$MODULE" -am -Dmaven.test.skip=true dependency:go-offline

COPY ${MODULE}/src/main/ ${MODULE}/src/main/
# Skips are for image assembly only: run the full Maven test suite separately.
RUN --mount=type=cache,target=/root/.m2,sharing=locked \
    mvn -B -ntp -pl "$MODULE" -am -Dmaven.test.skip=true package \
    && mkdir /out \
    && cp "$MODULE"/target/*.jar /out/app.jar

FROM eclipse-temurin:21-jre-noble@sha256:0d57a8e90c2bb844c5a8e1b2b71e11eff06dad7edcfd803cd21ed4ae203b2515 AS runtime
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --gid 10001 app \
    && useradd --uid 10001 --gid 10001 --no-create-home --home-dir /app --shell /usr/sbin/nologin app
WORKDIR /app
COPY --from=build --chmod=0444 /out/app.jar /app/app.jar
COPY --chmod=0555 docker/healthcheck.sh /app/healthcheck.sh
# Normalize Windows checkouts without relying on host executable-bit support.
RUN sed -i 's/\r$//' /app/healthcheck.sh && chmod 0555 /app/healthcheck.sh
# Compose supplies a writable tmpfs at /tmp when the root filesystem is read-only.
ENV TMPDIR=/tmp
USER 10001:10001
ENTRYPOINT ["java", "-Djava.io.tmpdir=/tmp", "-jar", "/app/app.jar"]
