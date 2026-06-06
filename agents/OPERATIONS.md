# OPERATIONS.md

Guia de operacion de EtherBrain. Lee esto antes de ejecutar el runtime.

---

## Requisitos

- Java 21 (`java -version`)
- Maven wrapper incluido (`./mvnw -v`)
- API key del proveedor LLM que vayas a usar

---

## Variables de entorno

### LLM

| Variable | Descripcion | Default |
|---|---|---|
| `LLM_TYPE` | `openai` \| `anthropic` \| `gemini` \| `bedrock` | — |
| `LLM_URL` | URL BASE del proveedor (sin path — el codec lo añade) | — |
| `LLM_TOKEN` | API key / token (vacio para Ollama local) | `""` |
| `LLM_MODEL` | Nombre del modelo | — |
| `LLM_MAX_TOKENS` | Limite de tokens en la respuesta | `4096` |
| `LLM_TEMPERATURE` | Temperatura `0.0`–`2.0` (omitir = default del proveedor) | — |
| `LLM_TIMEOUT_SECONDS` | Timeout de llamada al LLM en segundos | `30` |

Existen 4 formatos reales de API LLM en el mercado. `LLM_TYPE` los identifica:

| `LLM_TYPE` | Codec | Estado | Proveedores |
|---|---|---|---|
| `openai` (default) | `OpenAiCodec` | ✅ | OpenAI, Groq, Deepseek, Mistral, Qwen, OpenRouter, Together AI, Fireworks, Ollama, LM Studio, vLLM — cualquier `/v1/chat/completions` |
| `anthropic` | `AnthropicCodec` | ✅ | Anthropic Claude directo o proxy que preserve el formato |
| `gemini` | `GeminiCodec` | ✅ | Google Gemini (via generativelanguage.googleapis.com) |
| `bedrock` | `BedrockCodec` | ✅ | AWS Bedrock (requiere SigV4 via cliente HTTP pre-configurado) |

Si `LLM_TYPE` no esta definido, se intenta inferir del path de la URL como fallback.

URLs de referencia por proveedor (URL base — sin path):

| Proveedor | `LLM_URL` |
|---|---|
| Anthropic | `https://api.anthropic.com` |
| OpenAI | `https://api.openai.com` |
| Groq | `https://api.groq.com/openai` |
| Cerebras | `https://api.cerebras.ai` |
| Deepseek | `https://api.deepseek.com` |
| Mistral | `https://api.mistral.ai` |
| OpenRouter | `https://openrouter.ai` |
| Ollama local | `http://localhost:11434` |

### Sesiones

| Variable | Descripcion | Default |
|---|---|---|
| `SESSION_DIR` | Directorio de sesiones persistentes (omitir = en memoria) | — |
| `SESSION_TTL_HOURS` | TTL de sesiones en disco | `168` (7 dias) |

### Agente

| Variable | Descripcion | Default |
|---|---|---|
| `AGENT_MAX_STEPS` | Maximo de iteraciones por sesion | `8` |
| `AGENT_SYSTEM_PROMPT` | Prompt de sistema configurable | — |
| `AGENT_RETRY_MAX` | Reintentos de tools fallidas | `0` |
| `AGENT_RETRY_DELAY_MS` | Espera entre reintentos en ms | `500` |

### Transporte HTTP

| Variable | Descripcion | Default |
|---|---|---|
| `HTTP_PORT` | Puerto del servidor HTTP | `8080` |
| `AUTH_TOKEN` | Token Bearer requerido en todas las peticiones (omitir = sin auth) | — |
| `HTTP_MAX_BODY_BYTES` | Limite de tamaño de body en bytes | `65536` |
| `HTTP_RATE_LIMIT_RPS` | Limite de peticiones por segundo (0 = sin limite) | `0` |
| `HTTPS_PORT` | Puerto HTTPS adicional | — |
| `HTTPS_KEYSTORE_PATH` | Ruta al keystore JKS | — |
| `HTTPS_KEYSTORE_PASSWORD` | Password del keystore | — |
| `HTTP_EVENT_QUEUE` | Capacidad de la cola de eventos async | `100` |

### Transporte MQTT

| Variable | Descripcion | Default |
|---|---|---|
| `MQTT_BROKER_URL` | URL del broker (`tcp://host:1883` o `ssl://host:8883`) | — |
| `MQTT_CLIENT_ID` | Identificador del cliente MQTT | `ether-brain-1` |
| `MQTT_USERNAME` | Usuario MQTT (omitir si no requiere auth) | — |
| `MQTT_PASSWORD` | Password MQTT | — |
| `MQTT_REQUEST_TOPIC` | Topic donde escucha mensajes entrantes | `agent/requests` |
| `MQTT_RESPONSE_TOPIC` | Topic base para respuestas | `agent/responses` |
| `MQTT_QOS` | Quality of Service `0`\|`1`\|`2` | `1` |
| `MQTT_KEEP_ALIVE_SECS` | Intervalo de keepalive en segundos | `60` |
| `MQTT_CLEAN_SESSION` | Sesion limpia en cada conexion | `true` |

### Observabilidad

| Variable | Descripcion | Default |
|---|---|---|
| `METRICS_ENABLED` | `true` → LoggingMetricsCollector; `false` → noop | `true` |
| `LOG_LEVEL` | Nivel global del runtime (`OFF`…`ALL`) | `INFO` |
| `LOG_FILE` | Ruta del archivo de log (activa rotacion) | — |
| `LOG_FILE_MAX_BYTES` | Tamaño maximo por archivo de log | `10485760` (10 MB) |
| `LOG_FILE_COUNT` | Numero de archivos rotativos | `5` |
| `LOG_FILE_APPEND` | Añadir al archivo existente al arrancar | `true` |

---

## Compilar

```bash
cd ether-brain/
./mvnw clean install -DskipTests
```

---

## Ejecutar

### Modo demo (sin LLM real)

```bash
cd ether-brain/
./mvnw -pl ether-brain-transport-cli exec:java -Dexec.args="What time is it?"
```

### Con Anthropic (turno unico)

```bash
export LLM_TYPE=anthropic
export LLM_URL=https://api.anthropic.com
export LLM_TOKEN=sk-ant-...
export LLM_MODEL=claude-opus-4-5

cd ether-brain/
./mvnw -pl ether-brain-transport-cli exec:java -Dexec.args="Quien eres?"
```

### Con OpenAI (turno unico)

```bash
export LLM_TYPE=openai
export LLM_URL=https://api.openai.com
export LLM_TOKEN=sk-...
export LLM_MODEL=gpt-4o-mini

cd ether-brain/
./mvnw -pl ether-brain-transport-cli exec:java -Dexec.args="Quien eres?"
```

### Con un LLM local compatible con OpenAI (Ollama)

```bash
export LLM_TYPE=openai
export LLM_URL=http://localhost:11434
export LLM_MODEL=llama3.2

cd ether-brain/
./mvnw -pl ether-brain-transport-cli exec:java -Dexec.args="Quien eres?"
```

### REPL interactivo

```bash
export LLM_TYPE=openai
export LLM_URL=https://api.cerebras.ai
export LLM_TOKEN=csk-...
export LLM_MODEL=gpt-oss-120b
export SESSION_DIR=/tmp/etherbrain-sessions

cd ether-brain/
./mvnw -pl ether-brain-transport-cli exec:java
```

Escribe mensajes en el prompt `>`. Escribe `exit` para salir.

### REPL con sesion con nombre

```bash
./mvnw -pl ether-brain-transport-cli exec:java -Dexec.args="--session proyecto-x"
```

La sesion `proyecto-x` se guarda en `SESSION_DIR/proyecto-x.json` si `SESSION_DIR` esta definido.

---

## Transporte HTTP

### Arrancar el servidor HTTP

```bash
export LLM_TYPE=openai
export LLM_URL=https://api.cerebras.ai
export LLM_TOKEN=csk-...
export LLM_MODEL=gpt-oss-120b
export HTTP_PORT=8080
export AUTH_TOKEN=mi-token-secreto   # opcional pero recomendado

java -jar ether-brain-transport-http/target/ether-brain-http.jar
```

### Llamar al agente

```bash
# Turno sincronico
curl -X POST http://localhost:8080/sessions/mi-sesion/run \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer mi-token-secreto" \
  -d '{"message":"¿Qué hora es?"}'

# SSE streaming
curl -N http://localhost:8080/sessions/mi-sesion/run/stream \
  -H "Authorization: Bearer mi-token-secreto"

# Evento async (fire-and-forget)
curl -X POST http://localhost:8080/events \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer mi-token-secreto" \
  -d '{"session_id":"tarea-larga","message":"Procesa el reporte"}'

# Cancelar sesion activa
curl -X DELETE http://localhost:8080/sessions/mi-sesion/cancel \
  -H "Authorization: Bearer mi-token-secreto"
```

---

## Transporte MQTT

### Arrancar el bridge MQTT

```bash
export LLM_TYPE=openai
export LLM_URL=https://api.cerebras.ai
export LLM_TOKEN=csk-...
export LLM_MODEL=gpt-oss-120b
export MQTT_BROKER_URL=tcp://localhost:1883
export MQTT_REQUEST_TOPIC=agent/requests
export MQTT_RESPONSE_TOPIC=agent/responses

java -jar ether-brain-transport-mqtt/target/ether-brain-mqtt.jar
```

### Enviar un mensaje y recibir respuesta (con Mosquitto CLI)

```bash
# Suscribirse al topic de respuesta en una terminal
mosquitto_sub -t "agent/responses/#" -v

# Publicar un mensaje en otra terminal
mosquitto_pub -t "agent/requests" \
  -m '{"session_id":"s1","message":"¿Qué hora es?"}'
```

Respuesta esperada en `agent/responses/s1`:
```json
{"session_id":"s1","answer":"Son las 15:42 UTC.","status":"ok"}
```

Si se omite `session_id`, se genera uno automaticamente (`mqtt-XXXXXXXX`).
Si se omite `reply_to`, la respuesta va a `{MQTT_RESPONSE_TOPIC}/{session_id}`.

---

## Persistencia de sesiones

Cuando `SESSION_DIR` esta definido, cada sesion se guarda como
`<SESSION_DIR>/<session-id>.json`. Ejemplo:

```json
{
  "messages" : [ {
    "role" : "USER",
    "content" : "Quien eres?",
    "toolCallId" : null
  }, {
    "role" : "ASSISTANT",
    "content" : "Soy EtherBrain...",
    "toolCallId" : null
  } ]
}
```

Para borrar una sesion, elimina el archivo correspondiente.

---

## Tests

```bash
cd ether-brain/
./mvnw test
```

Tests especificos:

```bash
./mvnw -pl ether-brain-core -Dtest=AgentLoopTest test
./mvnw -pl ether-brain-transport-mqtt -Dtest=MqttAgentBridgeTest test
```

Tests de arquitectura:

```bash
./mvnw -pl ether-brain-architecture-tests test
```

---

## Diagnosticar problemas comunes

### El runtime dice "using demo client"

`LLM_TYPE` no esta definido o tiene un valor desconocido. Verifica:

```bash
echo $LLM_TYPE
echo $LLM_URL
echo $LLM_TOKEN
```

### Error: "Missing required environment variable"

Falta una variable obligatoria. Las minimas son: `LLM_TYPE`, `LLM_URL`, `LLM_MODEL`.
`LLM_TOKEN` puede estar vacio para Ollama local.

### Error: "Model call timed out"

El proveedor LLM no respondio en el tiempo configurado (default: 30s).
- Verifica conectividad a la API del proveedor.
- Aumenta el timeout: `LLM_TIMEOUT_SECONDS=60`.

### Error de tool en los logs pero el loop continua

Comportamiento esperado. El runtime captura errores de tool, añade
un mensaje de error al historial y le permite al modelo decidir como
continuar. Busca lineas `WARN` con "tool ... failed" en los logs.

### "Max steps exceeded without final answer"

El modelo solicito mas de `AGENT_MAX_STEPS` (default: 8) iteraciones sin
producir una respuesta final. Causas posibles:
- El modelo entra en un bucle de tool calls.
- Una tool siempre falla y el modelo sigue reintentando.
- `AGENT_MAX_STEPS` demasiado bajo para la tarea.

### MQTT: el bridge no recibe mensajes

- Verifica que el broker este corriendo: `mosquitto_pub -t test -m hello`
- Verifica `MQTT_BROKER_URL`, `MQTT_REQUEST_TOPIC`
- Activa trazas: `LOG_LEVEL=FINE` (o descomenta `dev.rafex.etherbrain.mqtt.level=FINE` en `logging.properties`)

### HTTP: 401 Unauthorized

`AUTH_TOKEN` esta definido pero el header `Authorization: Bearer <token>` no coincide.

### HTTP: 429 Too Many Requests

`HTTP_RATE_LIMIT_RPS` alcanzado. Reduce la frecuencia de llamadas o sube el limite.

---

## Agregar una tool

1. Implementar la interfaz `Tool` en `ether-brain-tools-local`:

```java
public final class MiTool implements Tool {

    @Override
    public String name() { return "mi_tool"; }

    @Override
    public String description() {
        return "Descripcion clara para que el modelo sepa cuando usar esta tool.";
    }

    @Override
    public String inputSchema() {
        return """
            {
              "type": "object",
              "properties": {
                "param1": { "type": "string", "description": "..." }
              },
              "required": ["param1"]
            }
            """;
    }

    @Override
    public ToolResult execute(String arguments, ExecutionContext context) throws Exception {
        return new ToolResult(name(), true, "resultado");
    }
}
```

2. Registrar en `ApplicationBootstrap`:

```java
InMemoryToolRegistry toolRegistry = new InMemoryToolRegistry()
        .register(new EchoTool())
        .register(new CurrentTimeTool())
        .register(new MiTool());       // <- agregar aqui

AgentConfig agentConfig = AgentConfig.defaults(
        Set.of("echo", "current_time", "mi_tool"));  // <- habilitar aqui
```

---

## Modulos del proyecto

| Modulo | Rol |
|---|---|
| `ether-brain-ports` | Interfaces del dominio (contratos) |
| `ether-brain-core` | Loop del agente, prompt builder, politicas, metricas |
| `ether-brain-common` | Excepciones compartidas |
| `ether-brain-infra-memory` | Sesiones en memoria |
| `ether-brain-infra-http` | Cliente HTTP para proveedores LLM + 4 codecs |
| `ether-brain-infra-file` | Sesiones persistidas en archivos JSON |
| `ether-brain-tools-local` | Tools Java locales (EchoTool, CurrentTimeTool) |
| `ether-brain-tools-remote` | Tools de servicios externos (KnowledgeSearchTool) |
| `ether-brain-bootstrap` | Ensamblado del runtime desde env vars |
| `ether-brain-transport-cli` | Entrada CLI (REPL y turno unico) |
| `ether-brain-transport-http` | Entrada HTTP REST con Jetty 12 (SSE, async, seguridad) |
| `ether-brain-transport-mqtt` | Entrada MQTT con Eclipse Paho (Mosquitto-compatible) |
| `ether-brain-architecture-tests` | Verificacion de fronteras hexagonales (ArchUnit, 6 reglas) |
