# ROADMAP.md

Direccion del proyecto EtherBrain en el tiempo.

## Objetivo

Dar contexto de prioridad sin convertir esto en una lista de tickets.

## Hecho

- Contratos del runtime base: mensajes, requests, responses y tool calls.
- Loop de un solo agente con maximo de pasos y recuperacion de errores de tool.
- Tools locales: `EchoTool`, `CurrentTimeTool`.
- Trazas simples y politicas minimas de seguridad.
- `HttpModelClient` agnostico con los 4 codecs del mercado:
  - `OpenAiCodec` — cubre OpenAI, Groq, Deepseek, Mistral, Cerebras, OpenRouter, Ollama y cualquier `/v1/chat/completions`
  - `AnthropicCodec` — Anthropic Claude
  - `GeminiCodec` — Google Gemini
  - `BedrockCodec` — AWS Bedrock
- `FileSessionStore` para sesiones persistentes en JSON con RW locks.
- `ConversationState` con ventana de mensajes configurable.
- `AgentConfig` con `RemoteServiceConfig` para servicios externos.
- `ApplicationBootstrap` con 4 variables universales: `LLM_TYPE`, `LLM_URL`, `LLM_TOKEN`, `LLM_MODEL`.
- Loader de `.env` automatico en el bootstrap.
- `LLM_URL` es URL base — cada codec construye el path correcto.
- CLI con REPL interactivo y flag `--session`.
- `modelTimeout` aplicado en el loop con virtual threads.
- `KnowledgeSearchTool` con autenticacion JWT automatica contra faiss-poc.
- `FaissTokenManager` con refresh proactivo (90s antes de expirar) y reintento en 401.
- `TokenProvider` como puerto generico de autenticacion.
- Documentacion operativa: `OPERATIONS.md`, `COMMANDS.md`, `docs/`.
- **Primera integracion real con LLM validada** — Cerebras gpt-oss-120b:
  - Respuesta simple: OK
  - Sesion persistente entre procesos: OK
  - Tool call `current_time`: OK
  - (ver `docs/integracion-llm.md`)
- `systemPrompt` configurable via `AGENT_SYSTEM_PROMPT` env var.
- `modelTimeout` sincronizado con `HttpModelConfig` via `LLM_TIMEOUT_SECONDS`.
- `AGENT_MAX_STEPS` configurable via env var.
- TTL de sesiones en `FileSessionStore` via `SESSION_TTL_HOURS`.
- **`ether-brain-transport-http`** — API HTTP REST con Jetty 12.1.10:
  - SSE streaming (`GET /sessions/{id}/run/stream`)
  - Eventos async fire-and-forget (`POST /events`)
  - Cancelacion de loop activo (`DELETE /sessions/{id}/cancel`)
  - Seguridad: `AUTH_TOKEN` (Bearer), rate limiting, SSRF guard, HTTPS
- **`ether-brain-transport-mqtt`** — Bridge MQTT con Eclipse Paho:
  - Compatible con Mosquitto y cualquier broker MQTT 3.1/3.1.1
  - Un virtual thread por mensaje entrante
  - Tests sin broker real (RecordingBridge)
- **`LLM_TEMPERATURE`** configurable por env var — aplica a los 4 codecs.
- **`LLM_MAX_TOKENS`** configurable por env var.
- **`MetricsCollector`** como puerto del dominio:
  - `LoggingMetricsCollector` emite lineas `[METRIC]` via JUL
  - `noop()` singleton controlado por `METRICS_ENABLED`
- **Logging mejorado**:
  - `logging.properties` en classpath silencia Jetty, Paho, Jackson, AWS SDK a WARNING
  - `LOG_FILE` activa `FileHandler` con rotacion automatica
  - `LOG_FILE_MAX_BYTES`, `LOG_FILE_COUNT`, `LOG_FILE_APPEND`
- **ArchUnit** — 6 reglas de arquitectura hexagonal verificadas en cada build:
  - core aislado, ports puros, bootstrap sin transportes, transportes independientes entre si
- CI/CD con GitHub Actions (`.github/workflows/ci.yml`).
- Ejecucion paralela de sub-agentes (BatchedToolRequest + executeBatchedTools).
- Comunicacion agente-a-agente via AgentTool + sesiones aisladas.
- SSE streaming de respuestas token a token.

## Ahora

- Validar `ether-brain-transport-http` con un cliente HTTP real en produccion.
- Validar `ether-brain-transport-mqtt` con un broker Mosquitto real.
- Estrategia de resumen de historial cuando el contexto es largo.

## Despues

- Bedrock streaming (binary event stream, AWS SDK v2).
- Adaptador Micrometer / OpenTelemetry para `MetricsCollector`.
- `ether-brain-event-bus` — adaptadores para Kafka, SQS, AMQP y cron.

## Mas adelante

- Tool calling estructurado por proveedor.
- Handoffs o subagentes sobre el mismo runtime base.
- Integraciones externas adicionales como MCP si el dominio ya lo pide.
- Distribucion de agentes multi-nodo.

## No hacer por ahora

- Multiagente como feature principal.
- Memoria vectorial o RAG antes de validar el loop.
- Shell arbitrario o ejecucion remota sin politicas fuertes.
