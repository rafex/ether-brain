# STACK.md

Fuente de verdad de la base tecnologica de EtherBrain.

## Runtime

- Lenguaje: Java
- Version obligatoria: 21
- Build tool preferido: Maven

## Biblioteca base

- Biblioteca estandar de Java:
  opcion por defecto para HTTP, concurrencia, logging y colecciones.
- `java.net.http.HttpClient`:
  cliente HTTP saliente para llamadas a proveedores LLM.
- `java.util.logging` + `ether-logging-core`:
  baseline de logging programatico y formateo consistente sin frameworks
  externos.
- Virtual threads (`Thread.ofVirtual()`):
  usados en los transportes MQTT y HTTP para despachar mensajes con
  concurrencia sin bloqueo y sin gestionar un pool propio.

## Frameworks

- Ninguno en el nucleo:
  evitar frameworks pesados mientras el runtime base madura.
- Dependencias de test:
  se permiten cuando simplifican validacion, sin contaminar el dominio.

## Servidor HTTP

- **Jetty 12.1.10** (`org.eclipse.jetty:jetty-server`, `jetty-servlet`):
  servidor embebido para `ether-brain-transport-http`.
  Reemplaza `com.sun.net.httpserver` por soporte real de TLS/SNI,
  HTTP/2 y mayor control de threading.
  No se introduce en el dominio ni en bootstrap.

## Transporte MQTT

- **Eclipse Paho MQTT v3** (`org.eclipse.paho.client.mqttv3:1.2.5`):
  cliente MQTT para `ether-brain-transport-mqtt`.
  Compatible con Mosquitto y cualquier broker MQTT 3.1/3.1.1.
  No se introduce en el dominio ni en bootstrap.

## Serialización

- **Jackson Databind** (`com.fasterxml.jackson.core:jackson-databind`):
  presente en `ether-brain-infra-http`, `ether-brain-infra-file`
  y los modulos de transporte.
  Ausente del dominio (`ports`, `core`, `common`).

## Tests de arquitectura

- **ArchUnit** (`com.tngtech.archunit:archunit-junit5`):
  verifica las 6 reglas hexagonales en cada build en
  `ether-brain-architecture-tests`.

## Tests unitarios

- **JUnit 5** (`org.junit.jupiter`):
  framework de tests presente en todos los modulos.

## Infraestructura

- Persistencia inicial:
  en memoria (`InMemorySessionStore`).
- Persistencia con archivo:
  JSON en disco via `FileSessionStore` con RW locks.
- Observabilidad:
  `MetricsCollector` como puerto; `LoggingMetricsCollector` emite via JUL;
  future: Micrometer / OpenTelemetry como adaptador.
- Logging con rotacion:
  `FileHandler` de JUL activado via `LOG_FILE`; rotacion automatica
  con `LOG_FILE_MAX_BYTES` y `LOG_FILE_COUNT`.
- CI/CD:
  GitHub Actions (`.github/workflows/ci.yml`).

## Integraciones

- Proveedor LLM por HTTP:
  adaptador intercambiable detras de `ModelClient`.
  Codecs: `OpenAiCodec`, `AnthropicCodec`, `GeminiCodec`, `BedrockCodec`.
- Tools locales:
  implementaciones Java controladas por `ToolRegistry` y politicas.
- Tools remotas:
  `KnowledgeSearchTool` contra faiss-poc con autenticacion JWT.
- Transporte HTTP REST:
  Jetty 12 en `ether-brain-transport-http`.
- Transporte MQTT:
  Eclipse Paho en `ether-brain-transport-mqtt`.

## Restricciones

- Mantener compatibilidad con Java 21.
- No introducir dependencias que oculten el loop principal sin una
  decision explicita (ver `agents/DECISIONS.md`).
- Jackson, Jetty y Paho no deben aparecer en `ports`, `core` ni `common`.
- Nuevos transportes deben ser modulos independientes; no deben
  depender entre si ni del dominio excepto via bootstrap.
