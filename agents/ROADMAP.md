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
- `ether-brain-transport-http` — API HTTP REST con `com.sun.net.httpserver`.
- CI/CD con GitHub Actions (`.github/workflows/ci.yml`).
- Tests de `GeminiCodec` (7) y `BedrockCodec` (6).
- Tests de `HttpAgentServer.extractMessage` (6).

## Ahora

- Probar `ether-brain-transport-http` con un cliente HTTP real.
- Estrategia de resumen de historial cuando el contexto es largo.

## Despues

- Prueba de tool calls explícitos (que el modelo invoque `current_time`).
- Conectar faiss-poc end-to-end con `knowledge_search`.
- Observabilidad: metricas de latencia por step.

## Mas adelante

- Tool calling estructurado por proveedor.
- Streaming y observabilidad mas rica.
- Handoffs o subagentes sobre el mismo runtime base.
- Integraciones externas adicionales como MCP si el dominio ya lo pide.

## No hacer por ahora

- Multiagente como feature principal.
- Memoria vectorial o RAG antes de validar el loop.
- Shell arbitrario o ejecucion remota sin politicas fuertes.
