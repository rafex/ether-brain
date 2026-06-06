# ARCHITECTURE.md

Describe la arquitectura actual y objetivo de EtherBrain.

## Vision general

EtherBrain sigue una arquitectura hexagonal. El nucleo del dominio vive
aislado de proveedores de modelo, persistencia, CLI o integraciones
externas. El centro del sistema es el loop del agente, que construye el
contexto, consulta al modelo, decide si responder o ejecutar una tool y
repite hasta cumplir una condicion de corte.

La forma esperada del sistema es:

```
Usuario -> Session -> PromptBuilder -> ModelClient -> ModelResponse
-> ToolRegistry -> ToolExecutor -> ToolResult -> Session -> siguiente iteracion
```

## Modulos actuales

```
ether-brain-ports              # contratos del dominio (interfaces puras)
ether-brain-core               # loop, prompt builder, politicas
ether-brain-common             # excepciones compartidas
ether-brain-infra-memory       # sesiones en memoria
ether-brain-infra-http         # cliente HTTP + 4 codecs LLM
ether-brain-infra-file         # sesiones persistidas en JSON
ether-brain-tools-local        # tools Java locales
ether-brain-tools-remote       # tools de servicios externos
ether-brain-bootstrap          # ensamblado del runtime desde env vars
ether-brain-transport-cli      # entrada CLI (REPL y turno unico)
ether-brain-transport-http     # entrada HTTP REST (Jetty 12, SSE, eventos async)
ether-brain-transport-mqtt     # entrada MQTT (Eclipse Paho, Mosquitto)
ether-brain-architecture-tests # verificacion de fronteras hexagonales (ArchUnit)
```

## Puertos del dominio

- `ModelClient`:
  puerto de salida para generar respuestas desde un proveedor de modelo.
- `SessionStore`:
  puerto de salida para recuperar y persistir estado conversacional.
- `ToolRegistry`:
  puerto de consulta para descubrir tools habilitadas.
- `ResourceRegistry`:
  puerto de consulta y lectura para contexto externo direccionable.
- `PromptRegistry`:
  puerto de consulta para prompts reutilizables y futuras integraciones
  remotas.
- `ToolExecutor`:
  puerto de salida para ejecutar una tool concreta con contexto.
- `PolicyEngine`:
  puerto de dominio para validar limites de seguridad y ejecucion.
- `MetricsCollector`:
  puerto de observabilidad. Tres operaciones: `increment`, `record`, `gauge`.
  Implementaciones: `LoggingMetricsCollector` (JUL) y `noop()` (singleton).
  No introduce dependencias de frameworks en el dominio.

## Casos de uso del nucleo

- `AgentRuntime`:
  fachada principal para iniciar una ejecucion.
- `AgentLoop`:
  orquesta el ciclo paso a paso y aplica condiciones de corte.
- `PromptBuilder`:
  transforma estado, instrucciones y tools disponibles en una solicitud
  al modelo.
- `ExecutionContext`:
  encapsula sesion, configuracion, trazas y politicas activas.

## Adaptadores de entrada (transportes)

- `ether-brain-transport-cli`:
  REPL interactivo y modo turno unico. Fat jar `ether-brain-cli.jar`.
- `ether-brain-transport-http`:
  API REST con Jetty 12.1.10. Endpoints SSE, eventos async y cancelacion
  de sesiones. Seguridad via `AUTH_TOKEN`, rate limiting y guard SSRF.
  Fat jar `ether-brain-http.jar`.
- `ether-brain-transport-mqtt`:
  Bridge MQTT con Eclipse Paho v3. Consume mensajes de un topic de
  requests, despacha en virtual thread y publica respuesta en topic de
  respuesta. Fat jar `ether-brain-mqtt.jar`.

## Adaptadores de salida

- `HttpModelClient` con 4 codecs: `OpenAiCodec`, `AnthropicCodec`,
  `GeminiCodec`, `BedrockCodec`.
- `InMemorySessionStore` y `FileSessionStore`.
- `InMemoryToolRegistry` con `CompositeToolRegistry`.
- `LoggingMetricsCollector` y `MetricsCollector.noop()`.

## Flujo principal

1. Un adaptador de entrada crea o carga una sesion.
2. `AgentRuntime` construye un `ExecutionContext`.
3. `AgentLoop` arma un `ModelRequest` con `PromptBuilder`.
4. `ModelClient` devuelve una respuesta final o una solicitud de tool.
5. Si hay tool call, `ToolExecutor` ejecuta la tool y guarda su resultado
   en la conversacion.
6. El loop continua hasta respuesta final, maximo de pasos o timeout.

## Reglas de dependencia (verificadas por ArchUnit)

- El dominio no depende de adaptadores concretos.
- Los adaptadores implementan puertos definidos por el dominio.
- `ports` no depende de nada interno del proyecto.
- `bootstrap` no depende de ningun transporte.
- Los transportes no se importan entre si.
- Las tools no deben acceder directamente a infraestructura fuera de su
  adaptador sin pasar por politicas.
- Los formatos de respuesta del proveedor no deben filtrarse al dominio.
- Integraciones como MCP deben entrar mediante registros o proveedores
  especificos, no acopladas al `AgentLoop`.

## Observabilidad

- **Logging**: `ether-logging-core` sobre JUL. `logging.properties` en
  classpath silencia terceros (Jetty, Paho, Jackson, AWS SDK) a WARNING.
  `LOG_LEVEL` controla el nivel del root logger. `LOG_FILE` activa
  FileHandler con rotacion automatica.
- **Metricas**: `MetricsCollector` como puerto. Metricas emitidas:
  `http.requests`, `http.requests.errors`, `mqtt.messages.published`,
  `llm.generate`, `tool.execute`, etc.

## Riesgos actuales

- Parseo de salida del modelo puede ser fragil para respuestas inusuales.
  Mitigacion: contratos de salida controlados por codec.
- Crecimiento accidental del historial de conversacion.
  Mitigacion: `MessageWindow` y politicas de tamano.
- Bedrock streaming aun no implementado (binary event stream).
  Mitigacion: pendiente en roadmap.
