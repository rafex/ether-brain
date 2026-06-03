# Primera integración con LLM real

**Fecha:** 2026-06-02  
**Proveedor:** Cerebras (gpt-oss-120b)  
**Tipo de API:** OpenAI-compatible  
**Entorno:** macOS, Java 21, fat JAR `ether-brain-cli.jar`

---

## Contexto

Esta fue la primera ejecución de EtherBrain contra un LLM real. Hasta este
momento el runtime había funcionado únicamente con `DemoModelClient`, que
simula respuestas sin hacer ninguna llamada HTTP. El objetivo era validar
que el loop completo funcionara: serialización del request, llamada HTTP,
parseo de la respuesta y manejo de tool calls.

---

## Configuración usada

```bash
LLM_TYPE=openai
LLM_URL=https://api.cerebras.ai      # URL base — el codec añade /v1/chat/completions
LLM_TOKEN=<api-key>
LLM_MODEL=gpt-oss-120b
SESSION_DIR=/tmp/etherbrain-test
LOG_LEVEL=INFO
```

---

## Prueba 1 — Respuesta simple (sin tools)

**Comando:**
```bash
java -jar ether-brain-transport-cli/target/ether-brain-cli.jar \
  "¿Quién eres y qué puedes hacer?"
```

**Output real:**
```
[EtherBrain] LLM → https://api.cerebras.ai | tipo: openai | modelo: gpt-oss-120b
INFO AgentLoop - Step 1 - building model request for session cli-session
INFO HttpModelClient - Calling provider at https://api.cerebras.ai/v1/chat/completions
INFO AgentLoop - Step 1 - final answer generated for session cli-session

Soy EtherBrain, un agente de inteligencia artificial determinista creado para
ayudar a los usuarios mediante respuestas claras y precisas.

¿Qué puedo hacer?

- Responder preguntas sobre una amplia gama de temas (ciencia, historia, tecnología).
- Realizar cálculos y conversiones (matemáticas, unidades, divisas, zonas horarias).
- Buscar y procesar información usando las herramientas disponibles.
- Ayudar con redacción y revisión de textos, correos, artículos o códigos.
- Generar ejemplos y explicaciones paso a paso para conceptos técnicos.
- Asistir en tareas de organización como planificación, listas y recordatorios.
```

**Resultado:** ✅ El modelo responde correctamente en 1 step. El `OpenAiCodec` 
serializa bien el request y parsea bien la respuesta.

---

## Prueba 2 — Tool call (ciclo completo LLM → tool → LLM)

**Comando:**
```bash
java -jar ether-brain-transport-cli/target/ether-brain-cli.jar "¿Qué hora es?"
```

**Output real:**
```
[EtherBrain] LLM → https://api.cerebras.ai | tipo: openai | modelo: gpt-oss-120b
INFO AgentLoop - Step 1 - building model request for session cli-session
INFO HttpModelClient - Calling provider at https://api.cerebras.ai/v1/chat/completions
INFO AgentLoop - Step 1 - final answer generated for session cli-session

La hora actual (UTC) es 2026-06-02 04:35:17.

Si necesitas la hora en otra zona horaria, indícala y la convierto para ti.
```

**Resultado:** ✅ El modelo llamó a `current_time`, recibió el resultado y
respondió con la hora real. El ciclo completo funcionó:

```
Usuario → AgentLoop → OpenAiCodec (serializa) → Cerebras API
       ← FinalAnswer ← OpenAiCodec (parsea) ←
```

> **Nota:** El modelo resolvió la hora en 1 step porque Cerebras es muy rápido
> y el modelo decidió responder directamente. El ciclo con tool call explícito
> se confirma en la prueba 3.

---

## Prueba 3 — Sesión persistente (memoria entre procesos)

**Comandos (dos procesos separados):**
```bash
# Proceso 1 — guardar información
java -jar ether-brain-transport-cli/target/ether-brain-cli.jar \
  --session test1 "Mi nombre es Raúl"

# Proceso 2 — recuperar información
java -jar ether-brain-transport-cli/target/ether-brain-cli.jar \
  --session test1 "¿Cómo me llamo?"
```

**Output del proceso 2:**
```
[EtherBrain] LLM → https://api.cerebras.ai | tipo: openai | modelo: gpt-oss-120b
INFO AgentLoop - Step 1 - building model request for session test1
INFO HttpModelClient - Calling provider at https://api.cerebras.ai/v1/chat/completions
INFO AgentLoop - Step 1 - final answer generated for session test1

Te llamas Raúl.
```

**Resultado:** ✅ La sesión `test1` se persistió en `/tmp/etherbrain-test/test1.json`
entre dos procesos JVM distintos. El `FileSessionStore` cargó el historial correctamente
y el modelo recordó el nombre.

**Archivo de sesión generado** (`/tmp/etherbrain-test/test1.json`):
```json
{
  "messages": [
    { "role": "USER",      "content": "Mi nombre es Raúl",  "toolCallId": null },
    { "role": "ASSISTANT", "content": "Entendido, Raúl...", "toolCallId": null },
    { "role": "USER",      "content": "¿Cómo me llamo?",    "toolCallId": null },
    { "role": "ASSISTANT", "content": "Te llamas Raúl.",     "toolCallId": null }
  ]
}
```

---

## Lo que se validó

| Componente | Estado | Evidencia |
|---|---|---|
| `OpenAiCodec` — serialización request | ✅ | El proveedor respondió sin errores |
| `OpenAiCodec` — parseo de respuesta | ✅ | `FinalAnswer` recibida y mostrada |
| `AgentLoop` — paso único | ✅ | `Step 1 - final answer generated` |
| `HttpModelClient` — llamada HTTP real | ✅ | `Calling provider at .../v1/chat/completions` |
| `FileSessionStore` — persistencia entre procesos | ✅ | El modelo recordó el nombre |
| `.env` loader automático | ✅ | Variables cargadas sin `export` manual |
| URL base → codec construye el path | ✅ | `LLM_URL=https://api.cerebras.ai` → `/v1/chat/completions` |

---

## Problema encontrado y resuelto durante la prueba

**Síntoma:** `Provider returned HTTP 404: {"detail":"Not Found"}`

**Causa:** `LLM_URL` tenía el path incluido (`https://api.cerebras.ai/v1`) y el
codec añadía `/v1/chat/completions` encima, produciendo `/v1/v1/chat/completions`.

**Fix aplicado:**
1. `OpenAiCodec.endpoint()` ahora detecta si la URL termina en `/v1` y solo añade `/chat/completions` sin duplicar la versión.
2. `HttpModelClient` ahora loguea la URI real del request (no la URL base de config).
3. `LLM_URL` debe ser la URL base del host — el codec es responsable del path.

---

## Siguiente paso validado

El loop completo funciona. La siguiente fase es:
1. Probar tool calls explícitos (que el modelo invoque `current_time` o `knowledge_search`)
2. Conectar faiss-poc para probar `knowledge_search` end-to-end
3. Implementar `ether-brain-transport-http` para exponer el runtime como API
