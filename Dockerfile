# syntax=docker/dockerfile:1

# One Dockerfile for both services. MODULE picks which one to build:
#
#   docker build --build-arg MODULE=notification-api -t notification-api .
#   docker build --build-arg MODULE=delivery-worker  -t delivery-worker .
#
# The build context is the repository root, because the Maven build needs the parent pom and the
# shared events module.

ARG JAVA_VERSION=21

# --- build: compile and package the module with the Maven wrapper --------------------------------
FROM eclipse-temurin:${JAVA_VERSION}-jdk AS build
ARG MODULE
WORKDIR /workspace
COPY . .
# The local Maven repository lives in a build cache: dependencies are downloaded once per machine,
# not once per build. Tests are left to CI; this stage only packages.
RUN --mount=type=cache,target=/root/.m2 \
    test -n "${MODULE}" || { echo "Pass --build-arg MODULE=notification-api or delivery-worker"; exit 1; } \
    && chmod +x mvnw \
    && ./mvnw --batch-mode --no-transfer-progress --projects "${MODULE}" --also-make -DskipTests package \
    && cp "${MODULE}"/target/"${MODULE}"-*.jar application.jar

# --- layers: split the jar, so dependencies and application code become separate image layers ----
FROM eclipse-temurin:${JAVA_VERSION}-jre AS layers
WORKDIR /layers
COPY --from=build /workspace/application.jar application.jar
RUN java -Djarmode=tools -jar application.jar extract --layers --destination extracted

# --- runtime: a JRE, the application, and nothing else --------------------------------------------
FROM eclipse-temurin:${JAVA_VERSION}-jre
RUN useradd --system --uid 10001 --no-create-home application
WORKDIR /application
# Ordered from what changes least to what changes most: a code change rebuilds only the last layer.
COPY --from=layers /layers/extracted/dependencies/ ./
COPY --from=layers /layers/extracted/spring-boot-loader/ ./
COPY --from=layers /layers/extracted/snapshot-dependencies/ ./
COPY --from=layers /layers/extracted/application/ ./
USER application
# Exec form: the JVM is PID 1 and receives SIGTERM, so Spring Boot can shut down gracefully.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "application.jar"]
