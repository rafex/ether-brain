# EtherBrain — TODO / Roadmap

Estado actual del runtime y trabajo pendiente, ordenado por impacto.

---

## Capacidades para agentes autónomos y multi-agente

| Capacidad | Estado | Impacto | Módulo |
|---|---|---|---|
| AgentTool — un agente como tool del core, sin HTTP | ✅ | Alto | `ether-brain-core` |
| Cancelación de loop en progreso | ✅ | Medio | `ether-brain-ports`, `ether-brain-core` |
| Retry inteligente de tools fallidas | ✅ | Medio | `ether-brain-core` |
| Registro de agentes (descubrimiento) | ✅ | Medio | `ether-brain-core` |
| SSE streaming con eventos de progreso por step | ✅ | Medio | `ether-brain-transport-http` |
| StepListener — progreso en tiempo real | ✅ | Medio | `ether-brain-ports`, `ether-brain-core` |
| Loop reactivo — cola async + callback URL | ✅ | Alto | `ether-brain-transport-http` |
| Ejecución paralela de tool calls por turno | ✅ | Alto | `ether-brain-core` (`BatchedToolRequest`) |
| Trigger por eventos externos (Kafka, cron, SQS) | ⏳ | Alto | nuevo módulo `ether-brain-event-bus` |
| Streaming token a token (LLM chunked/SSE) | ⏳ | Medio | `ether-brain-infra-http` (codecs) |

---

## Detalle de lo implementado

### ✅ AgentTool (`AgentTool.java`)
Un `AgentRuntime` implementa la interfaz `AgentRunner` y puede envolverse como `AgentTool`
para que el orquestador lo invoque in-process sin overhead HTTP.

```java
AgentRuntime researcher = buildResearcherRuntime();
toolRegistry.register(new AgentTool(researcher));
enabledTools.add("researcher");
```

### ✅ Cancelación (`CancellationToken`)
El loop se puede cancelar desde cualquier hilo. El token se chequea al inicio de cada paso.

```bash
DELETE /sessions/{id}/cancel
→ {"cancelled":true}
```

```java
CancellationToken.Mutable token = CancellationToken.create();
runtime.run(sessionId, message, token);
// desde otro hilo:
token.cancel();
```

### ✅ Retry (`RetryPolicy` + `DefaultRetryPolicy`)
Configurable vía env vars. El retry re-ejecuta la tool sin preguntar al modelo.

```env
AGENT_RETRY_MAX=3
AGENT_RETRY_DELAY_MS=1000
```

Implementaciones disponibles: `RetryPolicy.none()`, `RetryPolicy.fixed()`,
`RetryPolicy.exponentialBackoff()`, `DefaultRetryPolicy`.

### ✅ Registro de agentes (`LocalAgentRegistry`)
Registry thread-safe en memoria. Permite descubrir agentes por nombre.

```java
LocalAgentRegistry registry = new LocalAgentRegistry();
registry.register(researcherRuntime);
registry.register(writerRuntime);
```

### ✅ SSE Streaming (`POST /sessions/{id}/run/stream`)
El servidor HTTP emite Server-Sent Events conforme el agente procesa.

```
data: {"type":"start","sessionId":"abc"}
data: {"type":"answer","content":"Respuesta final..."}
data: {"type":"done"}
```

```bash
curl -N -X POST http://localhost:8080/sessions/demo/run/stream \
  -H "Content-Type: application/json" \
  -d '{"message":"¿Qué es EtherBrain?"}'
```

### ✅ Loop reactivo / Eventos asíncronos (`POST /events`)
Encola mensajes para procesamiento asíncrono. Opcionalmente notifica via callback URL.

```bash
curl -X POST http://localhost:8080/events \
  -H "Content-Type: application/json" \
  -d '{
    "session_id":    "async-session",
    "message":       "Analiza este documento y resume los puntos clave",
    "callback_url":  "https://mi-servicio.com/webhook/agent-result"
  }'
→ {"queued":true,"position":1}
```

Configurable: `HTTP_EVENT_QUEUE=100` (capacidad de la cola).

---

## Pendiente (próximas iteraciones)

### ⏳ Ejecución paralela de sub-agentes

**Por qué no está completa:** el protocolo actual de los codecs retorna un solo `ToolRequest`
por turno. Para paralelismo real necesitamos:

1. `BatchedToolRequest implements ModelResponse` — múltiples tool calls en un turno
2. Codecs actualizados para extraer todas las tool calls de la respuesta del modelo
3. `AgentLoop` refactorizado para ejecutar el batch en paralelo via `CompletableFuture`

Workaround actual: el modelo puede llamar a varios `AgentTool`s en pasos secuenciales.
Para paralelismo real, configura múltiples agentes HTTP y usa `HttpProxyTool`.

### ⏳ Trigger por eventos externos

El endpoint `POST /events` es asíncrono pero todavía es pull-based (el cliente hace POST).
Para triggers verdaderos (Kafka, cron, webhook externo, queue SQS/RabbitMQ) se necesita
un nuevo módulo `ether-brain-event-bus` con adaptadores por fuente de eventos.

### ⏳ Streaming de tokens LLM

El SSE actual envía la respuesta completa en un solo evento `answer`.
Para streaming token-a-token se necesita:
1. `ModelClient` con método `streamGenerate(request, consumer)`
2. Codecs actualizados para parsear respuestas chunked/SSE del proveedor
3. `AgentLoop` que pasa el stream al transport en tiempo real

Esto es viable con los codecs OpenAI (API soporta `"stream": true`).

### ⏳ Comunicación agente-a-agente con contexto compartido

Los sub-agentes hoy tienen sesiones aisladas (no comparten historial).
Para colaboración profunda (un agente puede ver el historial de otro) se necesita
un `SharedSessionStore` o un bus de mensajes explícito entre agentes.

---

## Variables de entorno para las nuevas capacidades

```env
# Agente
AGENT_NAME=orchestrator          # nombre único del agente (default: agent)
AGENT_DESCRIPTION=...            # descripción para el modelo orquestador
AGENT_RETRY_MAX=3                # max reintentos por tool (default: 0)
AGENT_RETRY_DELAY_MS=500         # delay entre reintentos (default: 500ms)

# Servidor HTTP
HTTP_PORT=8080
HTTP_THREADS=4
HTTP_EVENT_QUEUE=100             # capacidad de la cola async de eventos
```

---

## Arquitectura multi-agente — topologías soportadas hoy

### 1. In-process (recomendado para latencia baja)
```
Orquestador
├── AgentTool(investigador)  → AgentRuntime in-process
└── AgentTool(redactor)      → AgentRuntime in-process
```

### 2. HTTP distribuido (para aislamiento / escalabilidad)
```
Orquestador
├── HttpProxyTool → POST http://investigador:8081/sessions/{id}/run
└── HttpProxyTool → POST http://redactor:8082/sessions/{id}/run
```

### 3. Híbrido
```
Orquestador
├── AgentTool(fast_agent)    → in-process (herramientas simples)
└── HttpProxyTool            → servicio externo (herramientas pesadas)
```

Ver `docs/multi-agent.md` para guía completa.
