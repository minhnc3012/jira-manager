# ── Stage 1: Build ───────────────────────────────────────────────────────
# Compiles the app and builds an optimized (minified, no dev-tools) Vaadin
# frontend bundle. Needs network access on first build: Maven downloads
# dependencies and the Vaadin plugin downloads Node.js/pnpm to bundle the
# frontend — this stage is the slow one, later builds reuse Docker's layer
# cache as long as pom.xml/src don't change.
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app

COPY pom.xml .
RUN mvn -B -ntp dependency:go-offline

COPY src ./src
RUN mvn -B -ntp clean package -Pproduction -DskipTests

# ── Stage 2: Runtime ─────────────────────────────────────────────────────
FROM eclipse-temurin:21-jre
WORKDIR /app

COPY --from=build /app/target/*.jar app.jar

# H2 file database lives under ./data (relative to the working dir) — mount
# a volume here so worklog/user data survives container restarts/rebuilds.
VOLUME ["/app/data"]

EXPOSE 8888
ENTRYPOINT ["java", "-jar", "app.jar"]
