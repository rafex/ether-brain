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
| Streaming token a token (LLM chunked/SSE) | ✅ | Medio | `ether-brain-infra-http` (OpenAI, Gemini, Anthropic) |
| Comunicación agente-a-agente con sesiones aisladas | ✅ | Alto | `ether-brain-core` (`AgentTool`) |
| Trigger por eventos externos (Kafka, cron, SQS) | ⏳ | Alto | nuevo módulo `ether-brain-event-bus` |

---

## Hardening y producción

| Item | Estado | Módulo |
|---|---|---|
| Race condition `retryCount` → `ConcurrentHashMap` | ✅ | `ether-brain-core` |
| Eventos SSE para batch de tools | ✅ | `ether-brain-core` |
| Memory leak `FileSessionStore` → striped locks | ✅ | `ether-brain-infra-file` |
| `AGENT_REGISTRY` static → instancia (test-isolation) | ✅ | `ether-brain-bootstrap` |
| Autenticación HTTP Bearer token | ✅ | `ether-brain-transport-http` |
| SSRF — validación de `callback_url` | ✅ | `ether-brain-transport-http` |
| Rate limiting por IP | ✅ | `ether-brain-transport-http` |
| Límite de body size | ✅ | `ether-brain-transport-http` |
| Separador `\|` frágil → `MessageConstants` (0x1E) | ✅ | `ether-brain-common` |
| `extractField()` duplicado → `JsonUtils` | ✅ | `ether-brain-common` |
| `max_tokens` hardcoded → `LLM_MAX_TOKENS` | ✅ | `ether-brain-bootstrap` |
| `temperature` hardcoded → `LLM_TEMPERATURE` | ✅ | `ether-brain-infra-http`, `ether-brain-bootstrap` |
| Javadoc desactualizado | ✅ | varios módulos |
| `ApplicationBootstrap` god class → 4 factories | ✅ | `ether-brain-bootstrap` |
| `com.sun.net.httpserver` → Jetty 12.1.10 | ✅ | `ether-brain-transport-http` |
| Bedrock streaming nativo (binary event stream) | ⏳ | `ether-brain-infra-http` |

---

## Observabilidad

| Item | Estado | Módulo |
|---|---|---|
| Puerto agnóstico `MetricsCollector` | ✅ | `ether-brain-ports` |
| `LoggingMetricsCollector` (structured log lines) | ✅ | `ether-brain-core` |
| `NoopMetricsCollector` (built-in, para tests) | ✅ | `ether-brain-ports` |
| `X-Request-ID` propagado a métricas | ✅ | `ether-brain-transport-http`, `ether-brain-core` |
| `System.out/err` reemplazado por `EtherLog` | ✅ | todos los módulos |
| Integración con Micrometer / OpenTelemetry | ⏳ | implementar `MetricsCollector` |

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
Incluye eventos individuales por tool call y resultado.

```
data: {"type":"start","sessionId":"abc"}
data: {"type":"tool_call","tool":"search","args":"{\"q\":\"java\"}"}
data: {"type":"tool_result","tool":"search","result":"..."}
data: {"type":"answer","content":"Respuesta final..."}
data: {"type":"done"}
```

```bash
curl -N -X POST http://localhost:8080/sessions/demo/run/stream \
  -H "Content-Type: application/json" \
  -d '{"message":"¿Qué es EtherBrain?"}'
```

### ✅ Ejecución paralela de tool calls (`BatchedToolRequest`)
Los codecs ahora detectan múltiples tool calls en un solo turno. El `AgentLoop`
ejecuta el batch en paralelo con `CompletableFuture` y consolida los resultados
antes de volver al modelo.

### ✅ Streaming token a token
Los tres codecs principales soportan streaming nativo:

| Codec | Protocolo | Estado |
|---|---|---|
| `OpenAiCodec` | `stream: true`, SSE `data: {...}` | ✅ |
| `GeminiCodec` | `streamGenerateContent?alt=sse` | ✅ |
| `AnthropicCodec` | `stream: true`, SSE `event: content_block_delta` | ✅ |
| `BedrockCodec` | binary event stream (AWS SDK) | ⏳ fallback bloqueante |

### ✅ Comunicación agente-a-agente (`AgentTool`)
Los sub-agentes tienen sesiones aisladas por defecto. Para delegación in-process
el orquestador registra el sub-agente como `AgentTool` y lo invoca como cualquier
otra herramienta; el historial de cada agente es independiente.

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
La `callback_url` es validada contra SSRF (IPs privadas bloqueadas por defecto).

### ✅ Servidor HTTP — Jetty 12.1.10
`com.sun.net.httpserver` reemplazado por Jetty 12.1.10:

- `VirtualThreadPool` — un hilo virtual por petición
- HTTPS opcional vía `HTTPS_PORT` + certificado
- Autenticación Bearer: `AUTH_TOKEN`
- Rate limiting por IP: `HTTP_RATE_LIMIT_RPM`
- Body size limit: `HTTP_MAX_BODY_BYTES`
- SSRF guard en callbacks: `HTTP_ALLOW_PRIVATE_CALLBACK=true` para deshabilitar

### ✅ Observabilidad agnóstica (`MetricsCollector`)
Puerto de métricas sin dependencia a Prometheus/Micrometer:

```java
// En AgentLoop
metricsCollector.increment("agent.run.total", "agent=" + config.name());
metricsCollector.record("agent.run.duration", duration, "agent=" + config.name());
```

Implementaciones disponibles:
- `LoggingMetricsCollector` — emite líneas estructuradas con EtherLog
- `MetricsCollector.noop()` — no-op para tests
- Implementa la interfaz para integrar Micrometer, OpenTelemetry, Datadog, etc.

---

## Pendiente (próximas iteraciones)

### ⏳ Trigger por eventos externos

El endpoint `POST /events` es asíncrono pero todavía es pull-based (el cliente hace POST).
Para triggers verdaderos (Kafka, cron, webhook externo, queue SQS/RabbitMQ) se necesita
un nuevo módulo `ether-brain-event-bus` con adaptadores por fuente de eventos.

| Adaptador | Fuente |
|---|---|
| `CronTriggerAdapter` | scheduler interno / cron expression |
| `KafkaConsumerAdapter` | Apache Kafka |
| `SqsPollerAdapter` | AWS SQS |
| `AmqpConsumerAdapter` | RabbitMQ / AMQP |

### ⏳ Bedrock streaming nativo

`BedrockCodec.generateStreaming()` usa el fallback bloqueante. El protocolo nativo de
Bedrock es un binary event stream que requiere `aws-sdk-java-v2`. Implementarlo añadiría
streaming real para Claude en Bedrock sin polling.

### ⏳ Integración Micrometer / OpenTelemetry

El puerto `MetricsCollector` permite implementar adapters sin cambiar el core:

```java
// Ejemplo adapter Micrometer
public final class MicrometerMetricsCollector implements MetricsCollector {
    private final MeterRegistry registry;
    @Override
    public void increment(String name, String... tags) {
        registry.counter(name, tags).increment();
    }
    // ...
}
```

---

## Variables de entorno

```env
# Agente
AGENT_NAME=orchestrator          # nombre único del agente (default: agent)
AGENT_DESCRIPTION=...            # descripción para el modelo orquestador
AGENT_RETRY_MAX=3                # max reintentos por tool (default: 0)
AGENT_RETRY_DELAY_MS=500         # delay entre reintentos (default: 500ms)

# Modelo LLM
LLM_URL=https://api.openai.com   # URL base del proveedor (required)
LLM_TOKEN=sk-...                 # API key
LLM_MODEL=gpt-4o                 # nombre del modelo
LLM_TYPE=openai                  # openai | anthropic | gemini | bedrock
LLM_MAX_TOKENS=4096              # límite de tokens en la respuesta
LLM_TEMPERATURE=0.7              # temperatura de muestreo (0.0 – 2.0)

# Servidor HTTP
HTTP_PORT=8080
HTTP_EVENT_QUEUE=100             # capacidad de la cola async de eventos
AUTH_TOKEN=secret                # Bearer token requerido (omitir = sin auth)
HTTP_RATE_LIMIT_RPM=60           # peticiones por minuto por IP (0 = deshabilitado)
HTTP_MAX_BODY_BYTES=65536        # límite de body en POST (default: 64 KB)
HTTP_ALLOW_PRIVATE_CALLBACK=true # permitir IPs privadas en callback_url

# HTTPS (opcional)
HTTPS_PORT=8443
TLS_KEYSTORE_PATH=/path/to/keystore.p12
TLS_KEYSTORE_PASSWORD=changeit

# Sesiones
SESSION_DIR=/var/data/sessions   # omitir = in-memory
SESSION_TTL_HOURS=24             # TTL para sesiones en disco
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
