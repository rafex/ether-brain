# Arquitectura de EtherBrain

EtherBrain es un runtime de agentes IA construido con arquitectura hexagonal
en Java 21, sin frameworks externos en el dominio.

---

## Estructura de módulos

```
ether-brain/
├── ether-brain-ports/          # Contratos del dominio (interfaces puras)
├── ether-brain-core/           # Loop del agente, prompt builder, políticas
├── ether-brain-common/         # Excepciones compartidas
├── ether-brain-infra-memory/   # Sesiones en memoria
├── ether-brain-infra-http/     # Cliente HTTP para proveedores LLM
│   └── codec/
│       ├── OpenAiCodec.java    # Formato OpenAI-compatible (80%+ del mercado)
│       ├── AnthropicCodec.java # Formato Anthropic Messages API
│       ├── GeminiCodec.java    # Formato Google Gemini
│       └── BedrockCodec.java   # Formato AWS Bedrock
├── ether-brain-infra-file/     # Sesiones persistidas en archivos JSON
├── ether-brain-tools-local/    # Tools Java locales (EchoTool, CurrentTimeTool)
├── ether-brain-tools-remote/   # Tools de servicios externos (KnowledgeSearchTool)
├── ether-brain-bootstrap/      # Ensamblado del runtime desde env vars
└── ether-brain-transport-cli/  # Punto de entrada CLI (REPL y turno único)
```

---

## Reglas de dependencias (hexagonal)

```
ports     → (ninguna dependencia externa)
core      → ports, common
infra-*   → ports              (adaptadores de infraestructura)
tools-*   → ports              (adaptadores de herramientas)
bootstrap → core, infra-*, tools-*
transport → bootstrap
```

**El dominio (`core`, `ports`) no depende de infraestructura.** Los codecs,
el cliente HTTP y los stores son adaptadores que implementan puertos.

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

## Variables de entorno

### LLM
| Variable | Descripción |
|---|---|
| `LLM_TYPE` | `openai` \| `anthropic` \| `gemini` \| `bedrock` |
| `LLM_URL` | URL base del proveedor (sin path — el codec lo añade) |
| `LLM_TOKEN` | API key o token |
| `LLM_MODEL` | Nombre del modelo |

### Sesiones
| Variable | Descripción |
|---|---|
| `SESSION_DIR` | Directorio para sesiones JSON persistentes (omitir = en memoria) |
| `LOG_LEVEL` | `OFF` \| `SEVERE` \| `WARNING` \| `INFO` \| `FINE` \| `ALL` |

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
| Virtual threads para timeout | Java 21 stdlib, sin dependencias externas |
| `FileSessionStore` con RW locks | Thread-safe sin sacrificar rendimiento |

Ver `agents/DECISIONS.md` para el registro completo de decisiones.
