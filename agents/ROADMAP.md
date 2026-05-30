# ROADMAP.md

Direccion del proyecto EtherBrain en el tiempo.

## Objetivo

Dar contexto de prioridad sin convertir esto en una lista de tickets.

## Hecho

- Contratos del runtime base: mensajes, requests, responses y tool calls.
- Loop de un solo agente con maximo de pasos y recuperacion de errores
  de tool.
- Tools locales de prueba y almacenamiento en memoria.
- Trazas simples y politicas minimas de seguridad.
- `HttpModelClient` agnostico con `AnthropicCodec` y `OpenAiCodec`.
- `FileSessionStore` para sesiones persistentes en JSON.
- `ConversationState` con ventana de mensajes configurable.
- `AgentConfig` con `RemoteServiceConfig` para servicios externos.
- `ApplicationBootstrap` listo con env vars (`MODEL_PROVIDER`, API keys,
  `SESSION_DIR`).
- CLI con REPL interactivo y flag `--session`.
- `modelTimeout` aplicado en el loop con virtual threads.
- Documentacion operativa: `OPERATIONS.md` y `COMMANDS.md`.

## Ahora

- Tests de integracion del loop completo con proveedor real (smoke test).
- Registrar `KnowledgeSearchTool` en bootstrap cuando faiss-poc este
  disponible en el VPS.

## Despues

- Pruebas de integracion del loop completo con modelo real.
- Estrategia de resumen de historial cuando el contexto es largo.
- `ether-brain-transport-http` para exponer el runtime como API HTTP.

## Mas adelante

- Tool calling estructurado por proveedor.
- Streaming y observabilidad mas rica.
- Handoffs o subagentes sobre el mismo runtime base.
- Integraciones externas adicionales como MCP si el dominio ya lo pide.

## No hacer por ahora

- Multiagente como feature principal.
- Memoria vectorial o RAG antes de validar el loop.
- Shell arbitrario o ejecucion remota sin politicas fuertes.
