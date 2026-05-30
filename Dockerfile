# ── Stage 1: build ────────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jdk-jammy AS build
WORKDIR /workspace

# Cache Maven dependencies before copying source
COPY ether-brain/mvnw ether-brain/mvnw.cmd ./ether-brain/
COPY ether-brain/.mvn ./ether-brain/.mvn
COPY ether-brain/pom.xml ./ether-brain/
COPY ether-brain/ether-brain-common/pom.xml ./ether-brain/ether-brain-common/
COPY ether-brain/ether-brain-ports/pom.xml ./ether-brain/ether-brain-ports/
COPY ether-brain/ether-brain-core/pom.xml ./ether-brain/ether-brain-core/
COPY ether-brain/ether-brain-infra-memory/pom.xml ./ether-brain/ether-brain-infra-memory/
COPY ether-brain/ether-brain-infra-http/pom.xml ./ether-brain/ether-brain-infra-http/
COPY ether-brain/ether-brain-infra-file/pom.xml ./ether-brain/ether-brain-infra-file/
COPY ether-brain/ether-brain-tools-local/pom.xml ./ether-brain/ether-brain-tools-local/
COPY ether-brain/ether-brain-tools-remote/pom.xml ./ether-brain/ether-brain-tools-remote/
COPY ether-brain/ether-brain-bootstrap/pom.xml ./ether-brain/ether-brain-bootstrap/
COPY ether-brain/ether-brain-transport-cli/pom.xml ./ether-brain/ether-brain-transport-cli/
COPY ether-brain/ether-brain-architecture-tests/pom.xml ./ether-brain/ether-brain-architecture-tests/

RUN cd ether-brain && ./mvnw dependency:go-offline -q --no-transfer-progress || true

# Copy source and build fat JAR
COPY ether-brain/ ./ether-brain/
RUN cd ether-brain && ./mvnw clean package -DskipTests --no-transfer-progress -q

# ── Stage 2: runtime ──────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jre-jammy
WORKDIR /app

COPY --from=build /workspace/ether-brain/ether-brain-transport-cli/target/ether-brain-cli.jar app.jar

# ── Default environment ───────────────────────────────────────────────────────
ENV MODEL_PROVIDER=demo
ENV MODEL_NAME=""
ENV SESSION_DIR=/app/sessions
ENV LOG_LEVEL=INFO

# Create session directory and run as non-root
RUN mkdir -p /app/sessions \
 && groupadd -r etherbrain \
 && useradd -r -g etherbrain -s /bin/false etherbrain \
 && chown -R etherbrain:etherbrain /app

USER etherbrain

ENTRYPOINT ["java", "-jar", "app.jar"]
# Default: single turn. Override CMD to pass args or start REPL.
CMD []
