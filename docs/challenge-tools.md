# Challenge: Cómo dar tools a EtherBrain

Análisis de todas las formas de exponer herramientas al runtime de agentes,
con pros, contras, tecnologías disponibles y cómo encajan con la filosofía
de independencia de herramientas de EtherBrain.

---

## Estado actual

EtherBrain tiene tres mecanismos hoy:

| Mecanismo | Ejemplo | Código Java requerido |
|---|---|---|
| Tool Java nativa | `EchoTool`, `CurrentTimeTool` | Sí — implementa `Tool` |
| Tool remota HTTP | `KnowledgeSearchTool` → faiss-poc | Sí — escribe el cliente |
| Tool externa (subprocess) | `tools.json` + cualquier CLI | No — solo JSON |

El problema con los dos primeros: **cada herramienta nueva requiere código Java en EtherBrain**.
El tercero (subprocess) resuelve la independencia pero tiene limitaciones serias.

---

## El espacio del problema

Dar tools a un agente tiene tres tensiones fundamentales:

```
Simplicidad ←────────────────────────→ Poder
  (JSON config)                    (protocolo completo)

Apertura ←───────────────────────────→ Seguridad
  (cualquier proceso)              (sandbox)

Estándar ←───────────────────────────→ Independencia
  (MCP, OpenAI)                    (protocolo propio)
```

La filosofía de EtherBrain apunta a: **máxima apertura con mínima fricción**,
sin atarse a un ecosistema concreto.

---

## Alternativa 1 — Subprocess (actual `tools.json`)

El agente ejecuta un proceso externo, captura stdout o un archivo.

```
AgentLoop
    │ tool_call(args)
    ▼
ExternalTool.execute()
    │ ProcessBuilder(["ether-ocr", "ocr", "${file}", "${__output__}"])
    ▼
OS process
    │ exit(0) + writes output
    ▼
ToolResult(text)
```

### Pros
- Cero código Java para añadir tools
- Cualquier lenguaje, cualquier binario instalado
- El `tools.json` es legible por humanos y editable sin compilar
- Separación total: EtherBrain no conoce el tool

### Contras
- **Un proceso nuevo por cada llamada** — overhead de fork/exec (~10-100ms)
- **Sin estado entre llamadas** — cada ejecución empieza desde cero
- **Sin streaming** — el agente espera a que el proceso termine
- **Sin comunicación bidireccional** — el tool no puede pedir al agente más información
- **Argumentos como strings** — no hay tipos, no hay validación antes de ejecutar
- **Seguridad básica** — cualquier tool puede ejecutar código arbitrario
- **Sin discovery dinámico** — el tool no puede anunciar sus capacidades en runtime

### Tecnologías
Cualquier CLI: Python, Go, Rust, Bash, Node.js, binarios nativos.

### Cuándo es suficiente
Tools simples, sin estado, de corta duración (< 5s). OCR, extracción de texto,
llamadas a APIs externas, scripts de transformación.

---

## Alternativa 2 — HTTP Tool Proxy (genérico)

EtherBrain tiene un `HttpProxyTool` genérico. Cualquier servicio HTTP que exponga
un schema OpenAPI/JSON se convierte en tool mediante configuración.

```yaml
# tools.yaml
- name: weather
  description: "Gets current weather for a city"
  endpoint: http://localhost:8090/weather
  method: POST
  input_schema:
    type: object
    properties:
      city: {type: string}
    required: [city]
```

```
AgentLoop
    │ tool_call({city: "Madrid"})
    ▼
HttpProxyTool.execute()
    │ POST http://localhost:8090/weather
    │ {"city": "Madrid"}
    ▼
{"temperature": 22, "condition": "sunny"}
    ▼
ToolResult(text)
```

### Pros
- Servicios ya existentes se convierten en tools sin cambiar EtherBrain
- El servicio puede estar en cualquier lenguaje
- **El servicio tiene estado** — puede mantener conexiones, caches, sesiones
- Más rápido que subprocess para llamadas frecuentes
- Fácil de escalar horizontalmente
- El servicio puede tener su propio ciclo de vida independiente

### Contras
- Requiere levantar y mantener el servicio HTTP
- Latencia de red (incluso en loopback)
- El formato de request/response debe ser acordado
- Manejo de errores HTTP más complejo que exit codes
- Autenticación/seguridad del endpoint

### Tecnologías
FastAPI (Python), Gin (Go), Express (Node.js), Actix (Rust), Spring Boot (Java).

### Cuándo es ideal
Tools con estado, servicios que ya existen (bases de datos, APIs propias),
tools que se llaman frecuentemente donde el overhead de subprocess es inaceptable.

---

## Alternativa 3 — MCP (Model Context Protocol)

Protocolo estándar de Anthropic para conectar tools, recursos y prompts a modelos.
Comunicación JSON-RPC sobre stdio o HTTP+SSE.

```
AgentLoop
    │
    ▼
McpToolAdapter (client)
    │ JSON-RPC {"method":"tools/call","params":{...}}
    │ via stdio (pipe) o HTTP
    ▼
MCP Server (cualquier lenguaje)
    │ implementa tools/list + tools/call
    ▼
{"content": [{"type": "text", "text": "..."}]}
    ▼
ToolResult
```

```python
# Servidor MCP en Python (3 líneas útiles)
from mcp.server.fastmcp import FastMCP
mcp = FastMCP("ether-ocr")

@mcp.tool()
def ocr_document(file_path: str) -> str:
    """Extracts text from PDF or image using ether-ocr."""
    ...
```

### Pros
- **Ecosistema creciente** — miles de servidores MCP ya publicados (GitHub, Slack, bases de datos, etc.)
- **Protocolo estándar** — interoperable con Claude Desktop, Cursor, y otros clientes
- **Discovery dinámico** — el servidor anuncia sus tools en runtime
- **Recursos y prompts** además de tools
- **Tipado fuerte** — JSON Schema validado en ambos lados
- **Sin reinventar la rueda** — el SDK existe para Python, TypeScript, Go, Java

### Contras
- **Acoplamiento con Anthropic** — si MCP cambia, hay que actualizar
- **Overhead de protocolo** — más complejo que un subprocess para tools simples
- **El servidor necesita implementar el protocolo** — no sirve cualquier CLI sin wrapper
- **Complejidad operacional** — gestionar procesos MCP adicionales
- **Menos maduro** en Java (el SDK oficial está en Python y TypeScript)

### Tecnologías
- SDKs oficiales: `@modelcontextprotocol/sdk` (TypeScript), `mcp` (Python)
- SDK Java: `io.modelcontextprotocol:mcp` (en desarrollo, no oficial)
- Servidores existentes: GitHub, Slack, PostgreSQL, Filesystem, Browser, etc.

### Cuándo es ideal
Cuando quieres aprovechar el ecosistema existente (conectar herramientas de terceros
sin escribir adaptadores), o cuando los servidores MCP ya están disponibles para
lo que necesitas.

---

## Alternativa 4 — SPI Java (ServiceLoader)

Ya existe para modelos. Extenderlo a tools: cualquier JAR en el classpath
que implemente la interfaz `Tool` se descubre automáticamente.

```
META-INF/services/dev.rafex.etherbrain.ports.tools.Tool
    → dev.rafex.ether.ocr.OcrDocumentTool
```

```java
// En ether-ocr (JAR separado)
public class OcrDocumentTool implements Tool {
    public String name() { return "ocr_document"; }
    public ToolResult execute(String args, ExecutionContext ctx) {
        // llama al binario ether-ocr internamente
    }
}
```

### Pros
- **Sin servidor extra** — todo corre en el mismo proceso JVM
- **Tipado fuerte** — el compilador verifica la implementación
- **Rendimiento máximo** — llamada directa en memoria, sin IPC
- **El tool accede al `ExecutionContext`** — puede leer agentConfig, sessionId, etc.
- **Fácil de testear** — tests unitarios Java normales

### Contras
- **Solo Java** — el tool debe estar escrito en un lenguaje JVM
- **Reinicio necesario** al añadir tools (el classpath no cambia en runtime)
- **Acoplamiento de versión** — el JAR debe compilar contra la misma versión de ports
- **Distribución más compleja** — hay que gestionar JARs adicionales

### Tecnologías
Java, Kotlin, Scala, Clojure (cualquier JVM). ServiceLoader estándar de Java.

### Cuándo es ideal
Tools de alta frecuencia donde el overhead de subprocess/HTTP es inaceptable,
o tools que necesitan acceso profundo al estado del agente.

---

## Alternativa 5 — Script inline (GraalVM / scripting)

El `tools.json` contiene código JavaScript (u otro lenguaje) que se ejecuta
en un runtime embebido (GraalVM Polyglot).

```json
{
  "name": "format_date",
  "description": "Formats a date string",
  "input_schema": {...},
  "script": "const d = new Date(args.date); return d.toLocaleDateString('es-ES');",
  "lang": "js"
}
```

### Pros
- La lógica simple vive en el JSON — un solo archivo de configuración
- No requiere proceso externo
- Acceso a las APIs del host si se permite

### Contras
- **Seguridad**: el script puede hacer cualquier cosa si no hay sandbox estricto
- **Rendimiento**: GraalVM añade 50-200MB al JVM
- **Mantenibilidad**: lógica compleja en strings JSON es difícil de mantener
- **Debugging difícil**: errores de runtime en scripts embebidos son opacos

### Tecnologías
GraalVM Polyglot, Nashorn (deprecado), Rhino, Jython (Python en JVM).

### Cuándo es ideal
Nunca para lógica compleja. Solo para transformaciones de datos triviales
donde añadir un proceso externo sería excesivo.

---

## Alternativa 6 — WebAssembly (WASM) plugins

Las tools se compilan a WASM y se ejecutan en un runtime embebido (Wasmtime, WAMR).

```
tool.wasm  →  WasmToolAdapter  →  host functions  →  ToolResult
```

### Pros
- **Sandbox nativo** — el módulo WASM no puede hacer nada fuera de lo que el host permite
- **Portable** — el mismo `.wasm` corre en cualquier plataforma
- **Cualquier lenguaje** que compile a WASM (Rust, Go, C, C++, Python via py2wasm)
- **Rendimiento próximo a nativo** sin fork/exec

### Contras
- **Ecosistema inmaduro** en Java — `wasmtime-java` existe pero es experimental
- **Interface host-guest compleja** — pasar strings/structs entre Java y WASM requiere glue code
- **Debugging difícil** — el tooling de debug WASM en JVM es limitado
- **Curva de aprendizaje alta** para quien escribe los tools

### Tecnologías
Wasmtime (Rust/Java bindings), WAMR, GraalVM WASM support (experimental).

### Cuándo es ideal
Aún no. El ecosistema WASM en JVM no está maduro. Candidato para 2026-2027.

---

## Alternativa 7 — Protocolo gRPC bidireccional

Las tools se exponen como servicios gRPC. EtherBrain actúa como cliente.

```protobuf
service ToolService {
  rpc Execute (ToolRequest) returns (ToolResponse);
  rpc Stream  (ToolRequest) returns (stream ToolChunk);  // streaming
}
```

### Pros
- **Tipado fuerte con Protobuf** — contrato claro entre agente y tool
- **Streaming nativo** — el tool puede enviar resultados parciales
- **Cualquier lenguaje** con soporte gRPC
- **Multiplexación** — múltiples llamadas en una conexión
- **Bidireccional** — el tool puede enviar eventos al agente

### Contras
- **Complejidad operacional** — requiere proto files, compilar stubs, gestionar servicios
- **Overkill** para tools simples
- **Portobuf** añade fricción para tools que "solo necesitan devolver texto"

### Tecnologías
grpc-java, grpc-python, grpc-go, grpc-rust. Protobuf 3.

### Cuándo es ideal
Tools que requieren streaming de resultados (transcripción de audio, generación
de documentos largos, monitoreo en tiempo real).

---

## Comparativa

| Criterio | Subprocess | HTTP Proxy | MCP | SPI Java | Script inline | WASM | gRPC |
|---|---|---|---|---|---|---|---|
| Sin código Java | ✅ | ✅ | ✅ | ❌ | ✅ | ✅ | ✅ |
| Cualquier lenguaje | ✅ | ✅ | ✅ | ❌ | ❌ | ✅ | ✅ |
| Sin servidor extra | ✅ | ❌ | ❌ | ✅ | ✅ | ✅ | ❌ |
| Estado entre llamadas | ❌ | ✅ | ✅ | ✅ | ❌ | ❌ | ✅ |
| Streaming | ❌ | ❌ | ✅ | ✅ | ❌ | ❌ | ✅ |
| Discovery dinámico | ❌ | ❌ | ✅ | ❌ | ❌ | ❌ | ❌ |
| Seguridad/sandbox | ❌ | ⚠️ | ⚠️ | ❌ | ❌ | ✅ | ⚠️ |
| Rendimiento | ⚠️ | ⚠️ | ⚠️ | ✅ | ✅ | ✅ | ✅ |
| Madurez en Java | ✅ | ✅ | ⚠️ | ✅ | ⚠️ | ❌ | ✅ |
| Ecosistema tools | ❌ | ❌ | ✅ | ❌ | ❌ | ❌ | ❌ |
| Complejidad de adopción | 🟢 baja | 🟡 media | 🟡 media | 🟢 baja | 🟡 media | 🔴 alta | 🔴 alta |

---

## Filosofía de EtherBrain: qué significa "independencia de herramientas"

La independencia de herramientas tiene tres niveles:

```
Nivel 1 — Independencia de implementación
  El agente no conoce CÓMO funciona la tool internamente.
  (Ya logrado con la abstracción Tool/ToolResult)

Nivel 2 — Independencia de lenguaje
  La tool puede estar escrita en cualquier lenguaje.
  (Parcialmente: subprocess + HTTP Proxy lo resuelven)

Nivel 3 — Independencia de ciclo de vida
  Las tools pueden aparecer, desaparecer y actualizarse
  sin reiniciar el agente.
  (No resuelto todavía)
```

La arquitectura hexagonal dice: **las tools son adaptadores, no el core**.
El core (AgentLoop, PromptBuilder, PolicyEngine) no debe saber si una tool
es un proceso local, un servicio HTTP, o un servidor MCP.

Esto significa que el puerto `Tool` debería mantenerse minimalista:

```java
public interface Tool {
    String name();
    String description();
    String inputSchema();
    ToolResult execute(String arguments, ExecutionContext context) throws Exception;
}
```

Y los mecanismos de descubrimiento (subprocess, HTTP, MCP, SPI) son todos
adaptadores de ese puerto — intercambiables.

---

## Propuesta: capa de descubrimiento unificada

En lugar de elegir un solo mecanismo, EtherBrain podría tener un `ToolSource`
que unifica todos:

```java
public interface ToolSource {
    /** Devuelve las tools disponibles en este momento. */
    List<Tool> discover();

    /** Si es capaz de recargarse dinámicamente en runtime. */
    default boolean isDynamic() { return false; }
}
```

Implementaciones:
- `JsonFileToolSource` — lee `tools.json`, crea `ExternalTool` (ya existe)
- `HttpToolSource` — descubre tools desde un endpoint HTTP
- `McpToolSource` — conecta a un servidor MCP y adapta sus tools
- `ClasspathToolSource` — ServiceLoader sobre `Tool` (extensión del SPI)

El bootstrap cargará todas las fuentes configuradas:

```bash
TOOL_SOURCES=json-file,mcp:stdio://ether-ocr-server,http:http://tools.local:8080
```

---

## Recomendación por caso de uso

| Caso | Mecanismo recomendado | Por qué |
|---|---|---|
| Tool simple, cualquier lenguaje, sin estado | **Subprocess (actual)** | Cero infraestructura, máxima simplicidad |
| Tool con estado, llamadas frecuentes | **HTTP Proxy** | Sin overhead de fork, el servicio gestiona su estado |
| Conectar herramientas del ecosistema (GitHub, DB, etc.) | **MCP** | Ecosistema de servidores ya existe |
| Tool de alto rendimiento dentro de JVM | **SPI Java** | Llamada directa en memoria |
| Tool con resultados en streaming | **gRPC o MCP** | Protocolo lo soporta nativamente |
| Tool que necesita sandbox estricto | **WASM** (futuro) | Sandbox nativo, esperar madurez del ecosistema |

---

## El siguiente paso natural para EtherBrain

Dado el estado actual y la filosofía del proyecto, el camino más valioso
en este momento no es agregar más mecanismos sino **hacer más robusto el que existe**:

1. **`HttpProxyTool`** — permite que cualquier servicio HTTP se convierta en tool
   sin subprocess. Complementa el subprocess para servicios con estado.

2. **Recarga dinámica del `tools.json`** — el agente detecta cambios en el archivo
   y recarga las tools sin reiniciar.

3. **MCP client adapter** — permite conectar cualquier servidor MCP existente
   como fuente de tools. Acceso inmediato a un ecosistema de cientos de tools.

Estos tres juntos cubren el 95% de los casos de uso sin depender de un
ecosistema concreto, manteniendo la arquitectura hexagonal y la filosofía
de independencia de herramientas.
