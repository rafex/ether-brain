# Multi-agente en EtherBrain

Guía completa para crear agentes autónomos que se colaboran entre sí.

---

## ¿Qué soporta el runtime hoy?

| Capacidad | Disponible |
|---|---|
| Agente único con herramientas | ✅ |
| Cancelación de loop desde hilo externo | ✅ |
| Retry automático de tools fallidas | ✅ |
| SSE streaming de respuestas | ✅ |
| Eventos asíncronos (fire-and-forget) | ✅ |
| Sub-agente como tool in-process (`AgentTool`) | ✅ |
| Registro de agentes por nombre | ✅ |
| Sub-agente como tool HTTP (`HttpProxyTool`) | ✅ |
| Ejecución paralela de sub-agentes (`BatchedToolRequest`) | ✅ |
| Streaming token a token | ✅ |
| Trigger por MQTT (Mosquitto) | ✅ |
| Trigger por Kafka / SQS / AMQP / cron | ⏳ |

---

## Topología 1: In-process (recomendado)

Múltiples `AgentRuntime` corriendo en el mismo proceso JVM.
El orquestador invoca a los sub-agentes a través de `AgentTool`.

**Ventajas:** latencia mínima, sin serialización HTTP, tokens compartidos.  
**Limitación:** comparte recursos de memoria y CPU.

### Ejemplo: orquestador + investigador + redactor

```java
// 1. Construir sub-agentes con nombre y descripción
AgentRuntime investigador = new AgentRuntime(
    sessionStore,
    buildLoop(modelClient, investigadorTools),
    agentConfig,
    null,                          // sin memoria
    "investigador",                // nombre del agente (usado como nombre de tool)
    "Busca y sintetiza información de documentos y la web. Úsame cuando necesites datos."
);

AgentRuntime redactor = new AgentRuntime(
    sessionStore,
    buildLoop(modelClient, redactorTools),
    agentConfig,
    null,
    "redactor",
    "Genera textos estructurados y bien redactados a partir de información en bruto."
);

// 2. Registrar sub-agentes antes de construir el orquestador
ApplicationBootstrap.registerSubAgent(investigador);
ApplicationBootstrap.registerSubAgent(redactor);

// 3. El bootstrap los expone automáticamente como tools del orquestador
AgentRuntime orchestrator = new ApplicationBootstrap().bootstrap();
```

El orquestador verá dos tools con los nombres `investigador` y `redactor`.
El modelo decide cuándo delegar.

---

## Topología 2: HTTP distribuido

Cada agente corre en su propio proceso y expone un endpoint HTTP.

```json
// tools.json del orquestador
[
  {
    "type":        "http",
    "name":        "investigador",
    "description": "Agente especializado en investigación. Úsame cuando necesites buscar datos.",
    "endpoint":    "http://investigador-service:8081/sessions/${session_id}/run",
    "method":      "POST"
  },
  {
    "type":        "http",
    "name":        "redactor",
    "description": "Agente especializado en redacción. Úsame para generar textos estructurados.",
    "endpoint":    "http://redactor-service:8082/sessions/main/run",
    "method":      "POST"
  }
]
```

```bash
# Arrancar sub-agentes
AGENT_NAME=investigador HTTP_PORT=8081 java -jar ether-brain-http.jar
AGENT_NAME=redactor     HTTP_PORT=8082 java -jar ether-brain-http.jar

# Arrancar orquestador con los sub-agentes como tools HTTP
AGENT_NAME=orchestrator HTTP_PORT=8080 AGENT_TOOLS_FILE=tools.json java -jar ether-brain-http.jar
```

---

## Topología 3: Híbrida

Combina sub-agentes in-process (rápidos, livianos) con servicios HTTP externos (aislados, escalables).

```
Orquestador
├── AgentTool(clasificador)    → in-process, clasificación rápida
├── AgentTool(resumen)         → in-process, generación de resúmenes
├── HttpProxyTool(analizador)  → servicio Java separado, análisis intensivo
└── HttpProxyTool(extractor)   → microservicio Python, procesamiento especial
```

---

## Cancelación de un loop activo

```bash
# Cancelar el loop de una sesión en progreso
curl -X DELETE http://localhost:8080/sessions/mi-sesion/cancel
→ {"cancelled":true,"sessionId":"mi-sesion"}
```

Desde Java:
```java
CancellationToken.Mutable token = CancellationToken.create();

// Lanzar en un thread (o virtual thread)
Thread.ofVirtual().start(() -> {
    try {
        runtime.run(sessionId, mensaje, token);
    } catch (AgentException e) {
        System.out.println("Cancelado: " + e.getMessage());
    }
});

// Cancelar desde cualquier hilo
Thread.sleep(5000);
token.cancel();
```

---

## Retry automático de tools

Configura cuántas veces reintentar una tool fallida antes de reportar el error al modelo:

```env
AGENT_RETRY_MAX=3        # max reintentos (0 = sin retry, default)
AGENT_RETRY_DELAY_MS=500 # ms de espera entre reintentos (default: 500)
```

O desde código:
```java
RetryPolicy policy = RetryPolicy.exponentialBackoff(3, 200);
// intentará 3 veces: 200ms, 400ms, 800ms

AgentLoop loop = new AgentLoop(
    modelClient, toolRegistry, toolExecutor,
    promptBuilder, policyEngine,
    policy   // ← cuarto argumento
);
```

---

## SSE Streaming

Recibe la respuesta conforme se genera, sin esperar a que el agente termine:

```bash
curl -N -X POST http://localhost:8080/sessions/demo/run/stream \
  -H "Content-Type: application/json" \
  -d '{"message":"¿Cuál es el estado del proyecto?"}'
```

Respuesta:
```
data: {"type":"start","sessionId":"demo"}

data: {"type":"answer","content":"El proyecto EtherBrain..."}

data: {"type":"done"}
```

Desde JavaScript:
```javascript
const es = new EventSource('/sessions/demo/run/stream');
// o con POST via fetch + ReadableStream
```

---

## Eventos asíncronos (fire-and-forget)

Envía una tarea al agente sin esperar la respuesta. Ideal para flujos largos o notificaciones:

```bash
curl -X POST http://localhost:8080/events \
  -H "Content-Type: application/json" \
  -d '{
    "session_id":   "tarea-larga",
    "message":      "Procesa el reporte mensual y genera el resumen ejecutivo",
    "callback_url": "https://mi-app.com/webhooks/agente"
  }'
→ {"queued":true,"position":1}
```

Cuando el agente termina, POSTea a `callback_url`:
```json
{
  "sessionId": "tarea-larga",
  "answer":    "El resumen ejecutivo del reporte mensual es..."
}
```

Si falla:
```json
{
  "sessionId": "tarea-larga",
  "error":     "Max steps exceeded without final answer"
}
```

Configura la capacidad de la cola: `HTTP_EVENT_QUEUE=100`

---

## Variables de entorno de multi-agente

```env
# Identidad del agente
AGENT_NAME=orchestrator
AGENT_DESCRIPTION=Agente orquestador principal. Delega tareas a sub-agentes especializados.

# Retry
AGENT_RETRY_MAX=3
AGENT_RETRY_DELAY_MS=500

# Servidor HTTP
HTTP_PORT=8080
HTTP_EVENT_QUEUE=100

# Sub-agentes HTTP (via tools.json)
AGENT_TOOLS_FILE=/ruta/a/tools.json
```

---

## Capacidades pendientes

### Triggers externos avanzados

El transporte MQTT ya permite triggers desde cualquier broker Mosquitto-compatible.
Para triggers basados en colas de mensajería empresarial:

```
Kafka topic → EventBusAdapter → AgentEvent → AgentRuntime
SQS queue   → SQSAdapter      → AgentEvent → AgentRuntime
AMQP topic  → AMQPAdapter     → AgentEvent → AgentRuntime
Cron        → SchedulerAdapter → AgentEvent → AgentRuntime
```

Requiere módulo `ether-brain-event-bus` (pendiente en roadmap).
