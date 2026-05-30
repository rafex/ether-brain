# OPERATIONS.md

Guia de operacion de EtherBrain. Lee esto antes de ejecutar el runtime.

---

## Requisitos

- Java 21 (`java -version`)
- Maven wrapper incluido (`./mvnw -v`)
- API key del proveedor LLM que vayas a usar

---

## Variables de entorno

| Variable | Valores | Default | Descripcion |
|---|---|---|---|
| `MODEL_PROVIDER` | `anthropic` \| `openai` \| `openai-compatible` \| `demo` | `demo` | Proveedor de modelo |
| `MODEL_NAME` | cualquier string | segun proveedor | Identificador del modelo |
| `ANTHROPIC_API_KEY` | `sk-ant-...` | — | Requerida cuando `MODEL_PROVIDER=anthropic` |
| `OPENAI_API_KEY` | `sk-...` | — | Requerida cuando `MODEL_PROVIDER=openai` o `openai-compatible` |
| `OPENAI_BASE_URL` | URL completa | — | Requerida cuando `MODEL_PROVIDER=openai-compatible` |
| `SESSION_DIR` | ruta de directorio | (en memoria) | Si se define, las sesiones se persisten como JSON en esa ruta |
| `LOG_LEVEL` | `OFF` \| `SEVERE` \| `WARNING` \| `INFO` \| `FINE` \| `ALL` | `INFO` | Nivel de logging |

### Defaults de modelo por proveedor

| Proveedor | Model default |
|---|---|
| `anthropic` | `claude-opus-4-5` |
| `openai` | `gpt-4o-mini` |
| `openai-compatible` | sin default — `MODEL_NAME` es obligatorio |
| `demo` | sin LLM real |

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
export MODEL_PROVIDER=anthropic
export ANTHROPIC_API_KEY=sk-ant-...
export MODEL_NAME=claude-opus-4-5        # opcional, es el default

cd ether-brain/
./mvnw -pl ether-brain-transport-cli exec:java -Dexec.args="Quien eres?"
```

### Con OpenAI (turno unico)

```bash
export MODEL_PROVIDER=openai
export OPENAI_API_KEY=sk-...
export MODEL_NAME=gpt-4o-mini

cd ether-brain/
./mvnw -pl ether-brain-transport-cli exec:java -Dexec.args="Quien eres?"
```

### Con un LLM local compatible con OpenAI (ollama, etc.)

```bash
export MODEL_PROVIDER=openai-compatible
export OPENAI_BASE_URL=http://localhost:11434/v1/chat/completions
export MODEL_NAME=llama3.2

cd ether-brain/
./mvnw -pl ether-brain-transport-cli exec:java -Dexec.args="Quien eres?"
```

### REPL interactivo

```bash
export MODEL_PROVIDER=anthropic
export ANTHROPIC_API_KEY=sk-ant-...
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
```

---

## Diagnosticar problemas comunes

### El runtime dice "using demo client"

`MODEL_PROVIDER` no esta definido o tiene un valor desconocido. Verifica:

```bash
echo $MODEL_PROVIDER
echo $ANTHROPIC_API_KEY
```

### Error: "Missing required environment variable"

Falta una variable obligatoria para el proveedor elegido.
Ejemplo: `MODEL_PROVIDER=anthropic` requiere `ANTHROPIC_API_KEY`.

### Error: "Model call timed out"

El proveedor LLM no respondio en el tiempo configurado (default: 30s).
- Verifica conectividad a la API del proveedor.
- Aumenta el timeout via codigo si es necesario (ver `AgentConfig.defaults()`).

### Error de tool en los logs pero el loop continua

Comportamiento esperado. El runtime captura errores de tool, añade
un mensaje de error al historial y le permite al modelo decidir como
continuar. Busca lineas `WARN` con "tool ... failed" en los logs.

### "Max steps exceeded without final answer"

El modelo solicito mas de `maxSteps` (default: 8) iteraciones sin
producir una respuesta final. Causas posibles:
- El modelo entra en un bucle de tool calls.
- Una tool siempre falla y el modelo sigue reintentando.
- `maxSteps` demasiado bajo para la tarea.

---

## Agregar una tool

1. Implementar la interfaz `Tool` en `ether-brain-tools-local`:

```java
public final class MiTool implements Tool {

    @Override
    public String name() { return "mi_tool"; }

    @Override
    public String description() {
        return "Descripcion clara de lo que hace la tool para que el modelo sepa cuando usarla.";
    }

    @Override
    public String inputSchema() {
        return """
            {
              "type": "object",
              "properties": {
                "param1": { "type": "string", "description": "..." },
                "param2": { "type": "integer", "description": "..." }
              },
              "required": ["param1"]
            }
            """;
    }

    @Override
    public ToolResult execute(String arguments, ExecutionContext context) throws Exception {
        // Parsear arguments (JSON string) e implementar la logica
        // Ejemplo: usar context.agentConfig().remoteService("faiss-poc") para obtener URL
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
| `ether-brain-core` | Loop del agente, prompt builder, politicas |
| `ether-brain-common` | Excepciones compartidas |
| `ether-brain-infra-memory` | Sesiones en memoria |
| `ether-brain-infra-http` | Cliente HTTP para proveedores LLM |
| `ether-brain-infra-file` | Sesiones persistidas en archivos JSON |
| `ether-brain-tools-local` | Tools Java locales |
| `ether-brain-bootstrap` | Ensamblado con env vars |
| `ether-brain-transport-cli` | Punto de entrada CLI |
| `ether-brain-architecture-tests` | Validacion de fronteras hexagonales |
