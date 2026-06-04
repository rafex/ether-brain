# Tools externas — plug-and-play sin código Java

EtherBrain puede usar cualquier comando externo como tool del agente.
Solo necesitas un archivo JSON — sin tocar el código del agente.

---

## Cómo funciona

Define las tools en un archivo `tools.json` (o la ruta que indiques en `AGENT_TOOLS_FILE`).
Al arrancar, el agente las carga automáticamente y el modelo las puede invocar igual
que cualquier tool interna.

```
tools.json  ──→  ExternalToolLoader  ──→  InMemoryToolRegistry  ──→  AgentLoop
```

---

## Activación

```bash
# Opción A — tools.json en el directorio de trabajo (se detecta solo)
cp tools.json.example tools.json   # edita con tus tools
java -jar ether-brain-cli.jar "..."

# Opción B — ruta explícita
AGENT_TOOLS_FILE=/ruta/a/mis-tools.json java -jar ether-brain-cli.jar "..."
```

Al arrancar verás:
```
[EtherBrain] tool externa: ocr_document (cmd: ether-ocr ...)
[EtherBrain] 1 tools externas cargadas desde tools.json
```

---

## Formato del archivo

```json
[
  {
    "name":        "nombre_de_la_tool",
    "description": "Qué hace — el modelo la lee para decidir cuándo usarla",
    "input_schema": {
      "type": "object",
      "properties": {
        "argumento": {
          "type": "string",
          "description": "Descripción del argumento"
        }
      },
      "required": ["argumento"]
    },
    "command": ["programa", "${argumento}", "${__output__}"],
    "output":  "file"
  }
]
```

### Campos

| Campo | Tipo | Requerido | Descripción |
|---|---|---|---|
| `name` | string | ✅ | Identificador único (snake_case) |
| `description` | string | ✅ | Descripción que el modelo usa para decidir cuándo invocarla |
| `input_schema` | object | ✅ | JSON Schema de los argumentos — igual que las tools nativas |
| `command` | array | ✅ | Lista de tokens del comando con placeholders `${arg}` |
| `output` | string | — | `"stdout"` (default) o `"file"` |

### Placeholders en `command`

| Placeholder | Se sustituye por |
|---|---|
| `${nombre_arg}` | Valor del argumento del mismo nombre |
| `${__output__}` | Ruta a un archivo temporal — el proceso escribe ahí, EtherBrain lo lee |

### Modos de salida

| `output` | Comportamiento |
|---|---|
| `"stdout"` | Captura la salida estándar del proceso como resultado |
| `"file"` | Lee el contenido del archivo `${__output__}` como resultado |

---

## Ejemplo: ether-ocr

ether-ocr extrae texto de PDFs e imágenes (incluidos PDFs escaneados con OCR).

```json
[
  {
    "name": "ocr_document",
    "description": "Extracts text from a PDF or scanned image using ether-ocr. Supports digital PDFs (pdftotext) and scanned PDFs/images (Tesseract OCR). Returns extracted plain text.",
    "input_schema": {
      "type": "object",
      "properties": {
        "file_path": {
          "type": "string",
          "description": "Absolute path to the PDF or image file"
        },
        "lang": {
          "type": "string",
          "description": "Tesseract language codes separated by + (default: spa+eng)",
          "default": "spa+eng"
        }
      },
      "required": ["file_path"]
    },
    "command": ["ether-ocr", "ocr", "${file_path}", "${__output__}", "--lang", "${lang}"],
    "output": "file"
  }
]
```

### Uso desde el agente

```bash
# Arranca con el tools.json que incluye ocr_document
source .env.sh
java -jar ether-brain-cli.jar --session s1 \
  "Extrae el texto de /tmp/informe.pdf y dime de qué trata"
```

El modelo invoca `ocr_document(file_path="/tmp/informe.pdf")` automáticamente,
recibe el texto extraído y genera la respuesta.

### Requisito: ether-ocr disponible

```bash
# Instalar ether-ocr
cd /ruta/a/ether-ocr
pip install -e python

# Verificar
ether-ocr --help
```

Si no está en el PATH, define la ruta en `tools.json`:
```json
"command": ["/ruta/absoluta/al/ether-ocr", "ocr", "${file_path}", "${__output__}"]
```

O usa la variable de entorno para pasar PYTHONPATH al proceso:
```bash
TOOL_OCR_DOCUMENT_ENV_PYTHONPATH=/ruta/a/ether-ocr/python/src
```

---

## Ejemplo: script personalizado

Cualquier script o programa funciona como tool:

```json
[
  {
    "name": "analyze_code",
    "description": "Analyzes Java code quality using a custom script. Returns a report.",
    "input_schema": {
      "type": "object",
      "properties": {
        "file_path": {"type": "string", "description": "Path to the Java file"}
      },
      "required": ["file_path"]
    },
    "command": ["python3", "/scripts/analyze.py", "${file_path}"],
    "output": "stdout"
  },
  {
    "name": "translate_text",
    "description": "Translates text to Spanish using a local translation script.",
    "input_schema": {
      "type": "object",
      "properties": {
        "text": {"type": "string", "description": "Text to translate"},
        "target_lang": {"type": "string", "description": "Target language code", "default": "es"}
      },
      "required": ["text"]
    },
    "command": ["translate-cli", "--lang", "${target_lang}", "${text}"],
    "output": "stdout"
  }
]
```

---

## Variables de entorno por tool

Para pasar variables de entorno específicas a una tool, usa el prefijo
`TOOL_{NOMBRE_EN_MAYUSCULAS}_ENV_{VARIABLE}`:

```bash
# Para la tool "ocr_document"
TOOL_OCR_DOCUMENT_ENV_PYTHONPATH=/ruta/a/ether-ocr/python/src
TOOL_OCR_DOCUMENT_ENV_TESSDATA_PREFIX=/usr/share/tessdata

# Para la tool "analyze_code"
TOOL_ANALYZE_CODE_ENV_JAVA_HOME=/usr/lib/jvm/java-21
```

---

## Diagnóstico

### La tool no aparece en el arranque

Verifica que `tools.json` existe en el directorio de trabajo o que `AGENT_TOOLS_FILE` está definido:
```bash
ls tools.json        # o
echo $AGENT_TOOLS_FILE
```

### El modelo no invoca la tool

Mejora la `description` para que sea más específica sobre cuándo usarla:
```json
"description": "Use this tool WHENEVER the user mentions a PDF, image, or asks to extract text from a document."
```

### El comando falla con "No such file or directory"

Usa la ruta absoluta en el comando:
```json
"command": ["/usr/local/bin/ether-ocr", "ocr", ...]
```

O verifica que el binario está en el PATH:
```bash
which ether-ocr
```

### La tool devuelve texto vacío

Verifica que el modo de salida coincide con lo que produce el comando:
- El proceso escribe en stdout → `"output": "stdout"`
- El proceso escribe en el archivo `${__output__}` → `"output": "file"`
