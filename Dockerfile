# Two stages: a JDK compiles the jar, and only a JRE and the jar ship.

FROM eclipse-temurin:25-jdk AS build
WORKDIR /src
# The build files first, so dependencies are downloaded in their own layer
# and reused as long as the build files do not change.
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle gradle
RUN ./gradlew --no-daemon dependencies > /dev/null
COPY src src
# Tests are not run here: they need Docker themselves (Testcontainers), and
# CI runs them on every push before this image is ever built.
RUN ./gradlew --no-daemon bootJar -x test

FROM eclipse-temurin:25-jre
# Never run as root inside the container.
RUN groupadd --system app && useradd --system --gid app --no-create-home app
WORKDIR /app
COPY --from=build /src/build/libs/fx-ledger-*.jar app.jar
USER app
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
