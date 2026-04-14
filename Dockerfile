# ── Stage 1: build ────────────────────────────────────────────────────────────
FROM maven:3.9-eclipse-temurin-17 AS builder

WORKDIR /build

# Cache dependency downloads before copying source
COPY pom.xml .
RUN mvn dependency:go-offline -q

COPY src ./src
COPY config ./config

RUN mvn package -DskipTests -q

# ── Stage 2: runtime ──────────────────────────────────────────────────────────
FROM eclipse-temurin:17-jre-jammy

WORKDIR /app

COPY --from=builder /build/target/catan-1.0-jar-with-dependencies.jar app.jar

EXPOSE 4567

# PORT env var is read by Main.getHerokuAssignedPort(); set it here so the
# container always listens on 4567 regardless of host mapping.
ENV PORT=4567

CMD ["java", "-Dorg.eclipse.jetty.http.HttpCompliance=LEGACY", "-Dorg.eclipse.jetty.http.UriCompliance=UNSAFE", "-Dorg.eclipse.jetty.server.Request.maxFormContentSize=-1", "-Dorg.eclipse.jetty.server.Request.maxFormKeys=-1", "-Dorg.eclipse.jetty.http.HttpGenerator.SEND_SERVER_VERSION=false", "-Dorg.eclipse.jetty.http.HttpParser.STRICT=false", "-jar", "app.jar"]