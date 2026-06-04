# EtherBrain + faiss-poc — RAG y Memoria de Agente

Guía completa para usar EtherBrain con el knowledge base y la memoria semántica
de faiss-poc.

---

## Índice

1. [¿Qué es faiss-poc?](#qué-es-faiss-poc)
2. [Configuración mínima](#configuración-mínima)
3. [Preparar el namespace](#preparar-el-namespace)
4. [Subir documentos al knowledge base](#subir-documentos-al-knowledge-base)
5. [RAG — búsqueda semántica desde el agente](#rag--búsqueda-semántica-desde-el-agente)
6. [Memoria de agente (v2)](#memoria-de-agente-v2)
7. [Flujo completo de una sesión](#flujo-completo-de-una-sesión)
8. [Variables de entorno de referencia](#variables-de-entorno-de-referencia)
9. [Diagnóstico de problemas](#diagnóstico-de-problemas)

---

## ¿Qué es faiss-poc?

faiss-poc es un backend de búsqueda semántica con dos sistemas independientes:

```
faiss-poc
├── RAG  (v1 API) ─────────────────────────────────────────────
│   Documentos permanentes indexados con embeddings.
│   El agente los busca con la tool knowledge_search.
│   Persisten entre sesiones y reinicios del servidor.
│
└── Memoria de agente  (v2 API) ────────────────────────────────
    Scratchpad FAISS por sesión (RAM) + pgvector (largo plazo).
    Se llena automáticamente con cada turno del agente.
    El agente puede promover contexto importante a largo plazo.
```

Diferencia clave con `FileSessionStore`:

| | FileSessionStore | faiss-poc Memoria |
|---|---|---|
| Qué guarda | Historial exacto de mensajes | Turnos en formato semántico |
| Cómo recupera | Por orden cronológico | Por similitud semántica |
| Scope | Una sesión | Cross-sesión (vía commit) |
| Uso | Contexto de conversación | Memoria a largo plazo |

---

## Configuración mínima

Agrega al `.env` (ubicado en `ether-brain/ether-brain/`):

```bash
# LLM (ya debes tenerlo configurado)
LLM_TYPE=openai
LLM_URL=https://api.cerebras.ai
LLM_TOKEN=csk-...
LLM_MODEL=gpt-oss-120b

# faiss-poc — obligatorio para activar cualquier función
FAISS_BASE_URL=https://51.161.11.83:8443
FAISS_EMAIL=tu@email.com
FAISS_PASSWORD=tu_password
FAISS_SKIP_TLS_VERIFY=true          # necesario con certificado autofirmado

# RAG — activa la tool knowledge_search
FAISS_DEFAULT_NAMESPACE=mi-namespace

# Memoria — activa recall/remember automático + tool memory_commit
FAISS_MEMORY_NAMESPACE=mi-namespace  # puede ser el mismo namespace
```

Al arrancar el agente verás:

```
[EtherBrain] .env cargado: .env (8 variables)
[FaissTokenManager] Token refreshed — expires in 3600s
[EtherBrain] knowledge_search (RAG v1) → https://51.161.11.83:8443
[EtherBrain] memoria (v2) → namespace=mi-namespace ttl=1440m
```

---

## Preparar el namespace

Si no tienes un namespace todavía, créalo con `curl` antes de arrancar el agente:

```bash
# 1. Login — guarda el token
TOKEN=$(curl -sk -X POST https://51.161.11.83:8443/api/v1/auth/token \
  -H "Content-Type: application/json" \
  -d '{"email":"tu@email.com","password":"tu_password"}' \
  | grep -o '"access_token":"[^"]*"' | cut -d'"' -f4)

# 2. Crear namespace
curl -sk -X POST https://51.161.11.83:8443/api/v1/namespaces \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"name":"mi-namespace"}'

# 3. Verificar namespaces disponibles
curl -sk https://51.161.11.83:8443/api/v1/namespaces \
  -H "Authorization: Bearer $TOKEN" | python3 -m json.tool
```

> **Nota:** Los nombres de namespace solo admiten letras, dígitos, `-` y `_`.
> Ejemplos válidos: `agente`, `mi-proyecto`, `docs_java`.

---

## Subir documentos al knowledge base

### Desde el CLI (recomendado)

```bash
cd ether-brain/ether-brain
source .env.sh

# Un PDF
java -jar ether-brain-transport-cli/target/ether-brain-cli.jar \
  upload arquitectura.pdf --namespace mi-namespace

# Un markdown
java -jar ether-brain-transport-cli/target/ether-brain-cli.jar \
  upload README.md --namespace mi-namespace --tags docs,java

# Varios archivos a la vez
java -jar ether-brain-transport-cli/target/ether-brain-cli.jar \
  upload *.md --namespace mi-namespace --tags docs

# El namespace del .env se usa si no especificas --namespace
java -jar ether-brain-transport-cli/target/ether-brain-cli.jar \
  upload documento.pdf
```

**Formatos soportados:**

| Extensión | Procesamiento |
|---|---|
| `.pdf` | Extracción de texto con Apache PDFBox |
| `.txt` `.md` `.java` `.xml` `.json` `.yaml` `.csv` | Lectura directa UTF-8 |
| `.docx` `.xlsx` imágenes | ❌ No soportado — convierte a PDF o TXT primero |

**Salida esperada:**

```
[upload] arquitectura.pdf → 18432 caracteres extraídos
[✓] arquitectura.pdf → {"status":"uploaded","document_id":5,"chunks":46,"tags":0}

Resultado: 1 subidos, 0 errores
```

### Desde curl (alternativa)

```bash
# Subir texto plano directamente
curl -sk -X POST "https://51.161.11.83:8443/api/v1/namespaces/mi-namespace/upload/multipart" \
  -H "Authorization: Bearer $TOKEN" \
  -F "file=@documento.txt" \
  -F "tags=java" \
  -F "tags=arquitectura"
```

### Verificar documentos subidos

```bash
curl -sk "https://51.161.11.83:8443/api/v1/namespaces/mi-namespace/documents" \
  -H "Authorization: Bearer $TOKEN" | python3 -m json.tool
```

---

## RAG — búsqueda semántica desde el agente

Con `FAISS_DEFAULT_NAMESPACE` configurado, el agente tiene la tool `knowledge_search`
disponible. La invoca automáticamente cuando la pregunta requiere conocimiento específico.

### Ejemplo de conversación

```bash
source .env.sh
java -jar ether-brain-transport-cli/target/ether-brain-cli.jar \
  --session rag-test "¿Qué dice la arquitectura sobre los puertos y adaptadores?"
```

El agente internamente hace:

```
Step 1 — building model request
  → Modelo decide llamar knowledge_search
  → knowledge_search(query="puertos y adaptadores arquitectura", namespace="mi-namespace")
  → faiss-poc devuelve 5 chunks relevantes
Step 2 — tool result injected
  → Modelo genera respuesta con el contexto encontrado
Step 2 — final answer generated
```

### Parámetros avanzados

El modelo puede usar parámetros opcionales en `knowledge_search`:

```
# Búsqueda estándar (semántica)
knowledge_search(query="Java records")

# Con reranking (más preciso, ~500ms extra)
knowledge_search(query="Java records", use_rerank=true)

# Híbrido semántico + keyword (mejor para términos exactos de código)
knowledge_search(query="implements Comparable", use_bm25=true)

# Namespace diferente al default
knowledge_search(query="deploy kubernetes", namespace="ops-docs")
```

---

## Memoria de agente (v2)

Con `FAISS_MEMORY_NAMESPACE` configurado, la memoria funciona en dos niveles:

### Nivel 1 — Automático (transparente)

```
Turno del usuario
    ↓
recall() — dual query: scratchpad FAISS + pgvector
    → inyecta contexto relevante silenciosamente en el system prompt
    ↓
agentLoop.run() — el modelo responde con ese contexto disponible
    ↓
remember() async — guarda "User: ... / Assistant: ..." en el scratchpad
    ↓
Respuesta
```

El primer turno de cada sesión crea una sesión faiss-poc:

```
[FaissMemory] Sesión creada: a1b2c3-... (namespace=mi-namespace, ttl=1440m)
```

### Prueba de memoria entre sesiones

```bash
# Sesión A — establece contexto
java -jar ether-brain-transport-cli/target/ether-brain-cli.jar \
  --session sesion-a "Me llamo Raúl y trabajo con Java 21 y arquitectura hexagonal"

# Sesión B — completamente nueva
java -jar ether-brain-transport-cli/target/ether-brain-cli.jar \
  --session sesion-b "¿Sabes algo sobre mis preferencias de desarrollo?"
```

> **¿Qué pasa?** La sesión B no tiene historial de mensajes con la sesión A,
> pero `recall()` busca en el scratchpad de faiss-poc y puede encontrar el turno
> guardado por la sesión A si los textos son semánticamente similares.
>
> **Limitación:** El scratchpad es por sesión faiss-poc. Para que sesión B vea
> el contexto de sesión A con certeza, hay que hacer un `memory_commit` primero.

### Nivel 2 — Manual con `memory_commit`

El agente puede decidir guardar contexto importante en pgvector permanente
(disponible desde cualquier sesión futura via `recall()`):

```bash
java -jar ether-brain-transport-cli/target/ether-brain-cli.jar \
  --session sesion-a \
  "Recuerda permanentemente que prefiero usar records de Java 21 para modelos de datos \
   y arquitectura hexagonal con puertos y adaptadores"
```

El modelo invocará `memory_commit`:

```json
{
  "summary": "El usuario prefiere records de Java 21 y arquitectura hexagonal",
  "label": "preferencias-raul-2026",
  "k": 5
}
```

Resultado:
```
Committed 3 memory chunks to long-term storage.
Label: "preferencias-raul-2026".
This context will be available in future sessions.
```

Desde ese momento, **cualquier sesión nueva** tendrá ese contexto disponible
a través del `recall()` automático.

### Verificar el scratchpad de una sesión

```bash
# Crear sesión en faiss-poc y ver su estado
SESSION_ID=$(curl -sk -X POST \
  "https://51.161.11.83:8443/api/v2/namespaces/mi-namespace/sessions" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"ttl_minutes":60}' | grep -o '"session_id":"[^"]*"' | cut -d'"' -f4)

# Ver estado
curl -sk "https://51.161.11.83:8443/api/v2/namespaces/mi-namespace/sessions/$SESSION_ID" \
  -H "Authorization: Bearer $TOKEN" | python3 -m json.tool
```

---

## Flujo completo de una sesión

```
Usuario: "¿Cómo implemento un puerto de repositorio en hexagonal?"
         │
         ▼
[recall()] ──── POST /api/v2/namespaces/mi-ns/sessions/{sid}/query
         │      query="¿Cómo implemento un puerto de repositorio?"
         │      weight_session=0.4, weight_knowledge=0.6
         │      ← {items: [{text:"El puerto define la interfaz..."}, ...]}
         │
         ▼
[system prompt + memory context]
  "You are EtherBrain...
   ---
   [Contexto relevante de memoria]
   - El usuario prefiere records de Java 21 y arquitectura hexagonal
   - Puerto define la interfaz, adaptador la implementación
   [Fin del contexto de memoria]"
         │
         ▼
[agentLoop] ── decide llamar knowledge_search
         │     knowledge_search(query="puerto repositorio hexagonal")
         │     ← chunks de documentos subidos sobre arquitectura hexagonal
         │
         ▼
[respuesta del modelo con contexto de memoria + knowledge base]
         │
         ▼
[remember()] async ── POST /api/v2/namespaces/mi-ns/sessions/{sid}/memory
         │            texts=["User: ¿Cómo implemento...\nAssistant: Un puerto..."]
         │
         ▼
[FileSessionStore.save()] — guarda historial exacto de mensajes
```

---

## Variables de entorno de referencia

```bash
# ── OBLIGATORIO para cualquier función faiss-poc ──────────────
FAISS_BASE_URL=https://51.161.11.83:8443
FAISS_SKIP_TLS_VERIFY=true           # certificado autofirmado

# ── AUTH (elige una opción) ───────────────────────────────────
# Opción A — login automático con refresco (recomendado)
FAISS_EMAIL=tu@email.com
FAISS_PASSWORD=tu_password

# Opción B — token JWT estático (expira en 60 min)
# FAISS_AUTH_TOKEN=eyJ...

# Opción C — API key (no expira)
# FAISS_API_KEY=sk_...

# ── RAG (activa knowledge_search) ────────────────────────────
FAISS_DEFAULT_NAMESPACE=mi-namespace  # namespace donde buscar documentos

# ── MEMORIA (activa recall/remember + memory_commit) ─────────
FAISS_MEMORY_NAMESPACE=mi-namespace   # puede ser el mismo que DEFAULT
FAISS_SESSION_TTL_MINUTES=1440        # cuánto vive la sesión faiss-poc (24h)
```

---

## Diagnóstico de problemas

### Error 404 en login

```
faiss-poc login failed (HTTP 404)
```

**Causa:** El path de auth estaba mal en versiones anteriores.
**Solución:** Actualizar al commit `28e221a` o posterior.

### `knowledge_search` no aparece en las tools disponibles

```
[EtherBrain] FAISS_BASE_URL definido pero sin credenciales
```

**Causa:** Faltan `FAISS_EMAIL`+`FAISS_PASSWORD` o `FAISS_AUTH_TOKEN`.

### La memoria no se activa

```
[EtherBrain] memoria v2 desactivada
```

**Causa:** `FAISS_MEMORY_NAMESPACE` no está definido.
**Solución:** Añadirlo al `.env` (puede ser el mismo valor que `FAISS_DEFAULT_NAMESPACE`).

### El modelo no usa `knowledge_search` aunque hay documentos

El modelo solo invoca la tool si considera que necesita conocimiento externo.
Reformula la pregunta para hacerla más específica:

```bash
# Menos probable que use la tool
"¿Qué es hexagonal?"

# Más probable (requiere conocimiento del proyecto)
"¿Cómo está implementada la arquitectura hexagonal en este proyecto?"
```

También puedes guiar al modelo con el system prompt:

```bash
AGENT_SYSTEM_PROMPT="Eres un experto en el proyecto EtherBrain. \
Siempre busca en el knowledge base antes de responder preguntas técnicas. \
Usa knowledge_search con use_rerank=true cuando la precisión importa."
```

### El agente no recuerda conversaciones de sesiones anteriores

La memoria cross-sesión requiere que la información haya sido **commiteada** a pgvector.
El scratchpad de cada sesión faiss-poc es independiente.

Para que el contexto persista:

```bash
java -jar ether-brain-transport-cli/target/ether-brain-cli.jar \
  --session mi-sesion \
  "Guarda en memoria permanente: [información importante]"
```

O usa `FAISS_SESSION_TTL_MINUTES=0` (sin expiración) para mantener el scratchpad vivo
entre ejecuciones si el servidor faiss-poc no se reinicia.
