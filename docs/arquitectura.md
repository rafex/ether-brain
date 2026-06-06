# Arquitectura de EtherBrain

EtherBrain es un runtime de agentes IA construido con arquitectura hexagonal
en Java 21, sin frameworks externos en el dominio.

---

## Estructura de módulos

```
ether-brain/
├── ether-brain-ports/              # Contratos del dominio (interfaces puras)
├── ether-brain-core/               # Loop del agente, prompt builder, políticas
├── ether-brain-common/             # Excepciones compartidas
├── ether-brain-infra-memory/       # Sesiones en memoria
├── ether-brain-infra-http/         # Cliente HTTP para proveedores LLM
│   └── codec/
│       ├── OpenAiCodec.java        # Formato OpenAI-compatible (80%+ del mercado)
│       ├── AnthropicCodec.java     # Formato Anthropic Messages API
│       ├── GeminiCodec.java        # Formato Google Gemini
│       └── BedrockCodec.java       # Formato AWS Bedrock
├── ether-brain-infra-file/         # Sesiones persistidas en archivos JSON
├── ether-brain-tools-local/        # Tools Java locales (EchoTool, CurrentTimeTool)
├── ether-brain-tools-remote/       # Tools de servicios externos (KnowledgeSearchTool)
├── ether-brain-bootstrap/          # Ensamblado del runtime desde env vars
├── ether-brain-transport-cli/      # Entrada CLI (REPL y turno único)
├── ether-brain-transport-http/     # Entrada HTTP REST (Jetty 12, SSE, eventos async)
├── ether-brain-transport-mqtt/     # Entrada MQTT (Eclipse Paho, Mosquitto)
└── ether-brain-architecture-tests/ # Verificación de fronteras hexagonales (ArchUnit)
```

---

## Reglas de dependencias (hexagonal)

```
ports       → (ninguna dependencia externa)
core        → ports, common
infra-*     → ports              (adaptadores de infraestructura)
tools-*     → ports              (adaptadores de herramientas)
bootstrap   → core, infra-*, tools-*
transport-* → bootstrap
```

Los transportes son independientes entre sí: `transport-http` no depende de
`transport-mqtt` ni de `transport-cli`. Cada transporte es un adaptador de
entrada autónomo.

**El dominio (`core`, `ports`) no depende de infraestructura.** Los codecs,
el cliente HTTP y los stores son adaptadores que implementan puertos.

---

## Verificación automática de fronteras

`ether-brain-architecture-tests` usa ArchUnit para verificar 6 reglas en cada build:

| Regla | Descripción |
|---|---|
| `core_does_not_depend_on_infrastructure` | core solo conoce ports; nunca infra, tools, bootstrap ni transportes |
| `ports_do_not_depend_on_internals` | ports son contratos puros; sin dependencias internas del proyecto |
| `bootstrap_does_not_depend_on_transports` | bootstrap construye el runtime pero no conoce http/cli/mqtt |
| `http_transport_does_not_depend_on_other_transports` | http no importa mqtt ni cli |
| `mqtt_transport_does_not_depend_on_other_transports` | mqtt no importa http ni cli |
| `cli_transport_does_not_depend_on_other_transports` | cli no importa http ni mqtt |

---

## Flujo de una conversación

```
Usuario
  │
  ▼
AgentLoop.run(sessionId, userMessage)
  │
  ├─ 1. SessionStore.load(sessionId)          → historial previo
  ├─ 2. PromptBuilder.build(config, state)    → system + messages + tools
  ├─ 3. ModelClient.generate(request)         → llamada HTTP al LLM
  │       └─ ProviderCodec.buildHttpRequest() → serialización específica del proveedor
  │       └─ ProviderCodec.parseResponse()    → FinalAnswer o ToolRequest
  │
  ├─ Si FinalAnswer → devuelve respuesta al usuario
  │
  └─ Si ToolRequest:
        ├─ ToolExecutor.execute(toolName, args, context)
        ├─ Resultado añadido al historial como TOOL message
        └─ Volver al paso 2 (siguiente step)
  │
  └─ SessionStore.save(sessionId, state)      → persiste historial
```

---

## Los 4 formatos de API LLM

| `LLM_TYPE` | Codec | Path que construye | Auth |
|---|---|---|---|
| `openai` | `OpenAiCodec` | `base + /v1/chat/completions` | `Authorization: Bearer` |
| `anthropic` | `AnthropicCodec` | `base + /v1/messages` | `x-api-key` |
| `gemini` | `GeminiCodec` | `base + /v1beta/models/{model}:generateContent?key=...` | query param |
| `bedrock` | `BedrockCodec` | `base + /model/{model}/invoke` | SigV4 |

---

## Transportes disponibles

### CLI (`ether-brain-transport-cli`)
Punto de entrada por línea de comandos. Admite turno único y REPL interactivo.

```bash
java -jar ether-brain-cli.jar "¿Quién eres?"
java -jar ether-brain-cli.jar --session mi-sesion   # REPL
```

### HTTP (`ether-brain-transport-http`)
Servidor Jetty 12.1.10 que expone una API REST con soporte para SSE
y eventos asíncronos.

```
POST   /sessions/{id}/run          → turno síncrono
GET    /sessions/{id}/run/stream   → SSE streaming
POST   /events                     → fire-and-forget async
DELETE /sessions/{id}/cancel       → cancelar loop activo
```

Variables relevantes:
```
HTTP_PORT=8080
AUTH_TOKEN=<token>               # activa autenticación Bearer
HTTP_MAX_BODY_BYTES=65536        # límite de tamaño de body
HTTP_RATE_LIMIT_RPS=20           # límite de peticiones por segundo
HTTPS_PORT=8443                  # puerto HTTPS
HTTPS_KEYSTORE_PATH=...          # ruta al keystore JKS
HTTPS_KEYSTORE_PASSWORD=...
```

### MQTT (`ether-brain-transport-mqtt`)
Bridge sobre Eclipse Paho v3 (Mosquitto-compatible). Lee mensajes de un topic,
despacha cada uno en un virtual thread y publica la respuesta en un topic de respuesta.

Formato del mensaje de entrada:
```json
{ "session_id": "s1", "message": "¿Qué hora es?", "reply_to": "opcional/topic" }
```

Formato de la respuesta:
```json
{ "session_id": "s1", "answer": "...", "status": "ok" }
```

Variables relevantes:
```
MQTT_BROKER_URL=tcp://localhost:1883
MQTT_CLIENT_ID=ether-brain-1
MQTT_USERNAME=user
MQTT_PASSWORD=pass
MQTT_REQUEST_TOPIC=agent/requests
MQTT_RESPONSE_TOPIC=agent/responses
MQTT_QOS=1
```

---

## Observabilidad

### MetricsCollector (puerto del dominio)
`MetricsCollector` es un puerto con tres operaciones:
- `increment(name)` — contador
- `record(name, duration)` — temporizador
- `gauge(name, value)` — valor puntual

Implementaciones disponibles:
- `LoggingMetricsCollector` — emite líneas `[METRIC] counter/timer/gauge` via JUL
- `MetricsCollector.noop()` — singleton sin efecto

Controlado por `METRICS_ENABLED` (default: `true`).

### Logging
EtherBrain usa `java.util.logging` via `ether-logging-core`.

Jerarquía de configuración al arrancar:
1. `logging.properties` desde el classpath — silencia librerías de terceros (Jetty, Paho, Jackson, AWS SDK) a `WARNING`
2. `LOG_LEVEL` — nivel del root logger (default: `INFO`)
3. `LOG_FILE` — añade `FileHandler` con rotación automática al root logger

```
LOG_LEVEL=INFO                   # nivel global
LOG_FILE=/var/log/ether.log      # habilita escritura a archivo (rotativo)
LOG_FILE_MAX_BYTES=10485760      # 10 MB por archivo (default)
LOG_FILE_COUNT=5                 # archivos rotativos (default)
LOG_FILE_APPEND=true             # no sobreescribir al arrancar (default)
```

---

## Variables de entorno completas

### LLM
| Variable | Descripción | Default |
|---|---|---|
| `LLM_TYPE` | `openai` \| `anthropic` \| `gemini` \| `bedrock` | — |
| `LLM_URL` | URL base del proveedor (sin path — el codec lo añade) | — |
| `LLM_TOKEN` | API key o token | — |
| `LLM_MODEL` | Nombre del modelo | — |
| `LLM_MAX_TOKENS` | Límite de tokens en la respuesta | `4096` |
| `LLM_TEMPERATURE` | Temperatura del modelo (`0.0`–`2.0`); omitir = default del proveedor | — |
| `LLM_TIMEOUT_SECONDS` | Timeout de llamadas al LLM en segundos | `30` |

### Sesiones
| Variable | Descripción | Default |
|---|---|---|
| `SESSION_DIR` | Directorio para sesiones JSON persistentes (omitir = en memoria) | — |
| `SESSION_TTL_HOURS` | TTL de sesiones en disco | `168` (7 días) |

### Agente
| Variable | Descripción | Default |
|---|---|---|
| `AGENT_MAX_STEPS` | Máximo de iteraciones por sesión | `8` |
| `AGENT_SYSTEM_PROMPT` | Prompt de sistema configurable | — |
| `AGENT_RETRY_MAX` | Reintentos de tools fallidas | `0` |
| `AGENT_RETRY_DELAY_MS` | Espera entre reintentos | `500` |

### Observabilidad
| Variable | Descripción | Default |
|---|---|---|
| `METRICS_ENABLED` | `true` → LoggingMetricsCollector; `false` → noop | `true` |
| `LOG_LEVEL` | Nivel del root logger | `INFO` |
| `LOG_FILE` | Ruta del archivo de log con rotación | — |
| `LOG_FILE_MAX_BYTES` | Tamaño máximo por archivo | `10485760` |
| `LOG_FILE_COUNT` | Número de archivos rotativos | `5` |
| `LOG_FILE_APPEND` | Añadir al archivo existente | `true` |

### Knowledge base (faiss-poc)
| Variable | Descripción |
|---|---|
| `FAISS_BASE_URL` | URL del servicio faiss-poc |
| `FAISS_EMAIL` | Email para login automático con JWT |
| `FAISS_PASSWORD` | Password para login automático |
| `FAISS_AUTH_TOKEN` | JWT estático (alternativa) |
| `FAISS_SKIP_TLS_VERIFY` | `true` para certificados autofirmados |

---

## Decisiones de diseño clave

| Decisión | Razón |
|---|---|
| Sin frameworks en el dominio | El loop del agente es código Java puro, legible y controlable |
| Un codec por formato de API | No por proveedor — el 80%+ del mercado usa OpenAI-compatible |
| `LLM_URL` es URL base | El codec conoce el path — es su responsabilidad |
| Jackson solo en infra | El dominio no sabe que existe JSON |
| Virtual threads para concurrencia | Java 21 stdlib, sin dependencias externas |
| `FileSessionStore` con RW locks | Thread-safe sin sacrificar rendimiento |
| Jetty 12 para HTTP | `com.sun.net.httpserver` no soporta TLS/SNI/HTTP2 nativo |
| Eclipse Paho para MQTT | Cliente MQTT v3 maduro, compatible con Mosquitto, sin dependencias pesadas |
| `TEMPERATURE_UNSET = -1.0` | Sentinel permite backward-compat; cada codec usa su default si no se configura |
| `MetricsCollector` como puerto | Desacopla observabilidad del dominio; Micrometer/OTel en el futuro sin tocar core |
| `logging.properties` en classpath | Silencia librerías de terceros sin afectar `LOG_LEVEL` del runtime |

Ver `agents/DECISIONS.md` para el registro completo de decisiones.
