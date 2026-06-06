# Proveedores LLM soportados

EtherBrain soporta 4 formatos de API. Cada formato tiene su propio codec.
La mayoría de proveedores usan el formato OpenAI-compatible.

---

## Cómo configurar

Variables de entorno principales (en tu `.env` o exportadas en el shell):

```bash
LLM_TYPE=openai          # openai | anthropic | gemini | bedrock
LLM_URL=https://api.cerebras.ai   # URL BASE del proveedor — sin path
LLM_TOKEN=csk-...        # API key (vacío para Ollama local)
LLM_MODEL=gpt-oss-120b   # nombre del modelo
```

Variables opcionales para ajustar el comportamiento del modelo:

```bash
LLM_MAX_TOKENS=4096      # límite de tokens en la respuesta (default: 4096)
LLM_TEMPERATURE=0.7      # temperatura del modelo 0.0–2.0 (omitir = default del proveedor)
LLM_TIMEOUT_SECONDS=30   # timeout de la llamada HTTP al LLM (default: 30s)
```

> **`LLM_URL` es la URL base** — el codec añade el path correcto automáticamente.
> No incluyas `/v1/chat/completions` ni ningún otro path.

> **`LLM_TEMPERATURE`** — si no se define, cada codec usa el default del proveedor.
> `GeminiCodec` usa `0.7` cuando la variable no está definida; los demás codecs
> omiten el campo y dejan que el proveedor decida.

---

## Formato OpenAI-compatible (`LLM_TYPE=openai`)

El codec añade `/v1/chat/completions` a la URL base.

| Proveedor | `LLM_URL` | Modelos destacados |
|---|---|---|
| **Cerebras** ⭐ | `https://api.cerebras.ai` | `gpt-oss-120b` |
| **OpenAI** | `https://api.openai.com` | `gpt-4o`, `gpt-4o-mini` |
| **Groq** | `https://api.groq.com` | `llama-3.3-70b-versatile`, `mixtral-8x7b` |
| **Deepseek** | `https://api.deepseek.com` | `deepseek-chat`, `deepseek-reasoner` |
| **Mistral** | `https://api.mistral.ai` | `mistral-large-latest`, `open-mistral-7b` |
| **OpenRouter** | `https://openrouter.ai` | `anthropic/claude-opus-4-5`, `deepseek/deepseek-chat` |
| **Together AI** | `https://api.together.xyz` | `meta-llama/Llama-3-70b-chat-hf` |
| **Fireworks** | `https://api.fireworks.ai` | `accounts/fireworks/models/llama-v3p1-70b-instruct` |
| **Ollama local** | `http://localhost:11434` | `llama3.2`, `mistral`, `codellama` |
| **LM Studio local** | `http://localhost:1234` | cualquier modelo GGUF |
| **vLLM local** | `http://localhost:8000` | cualquier modelo HuggingFace |

> ⭐ Probado en producción con EtherBrain el 2026-06-02.

---

## Formato Anthropic (`LLM_TYPE=anthropic`)

El codec añade `/v1/messages` a la URL base.
Auth: `x-api-key` header (no Bearer).

| Proveedor | `LLM_URL` | Modelos destacados |
|---|---|---|
| **Anthropic** | `https://api.anthropic.com` | `claude-opus-4-5`, `claude-haiku-4-5` |

---

## Formato Google Gemini (`LLM_TYPE=gemini`)

El codec construye: `{base}/v1beta/models/{LLM_MODEL}:generateContent`.
Auth: API key como query parameter `?key=...`.

| Proveedor | `LLM_URL` | Modelos destacados |
|---|---|---|
| **Google Gemini** | `https://generativelanguage.googleapis.com` | `gemini-2.0-flash`, `gemini-1.5-pro` |

---

## Formato AWS Bedrock (`LLM_TYPE=bedrock`)

El codec construye: `{base}/model/{LLM_MODEL}/invoke`.
Auth: requiere SigV4 — configura credenciales AWS en el entorno.

| Proveedor | `LLM_URL` | Modelos destacados |
|---|---|---|
| **AWS Bedrock** | `https://bedrock-runtime.{region}.amazonaws.com` | `anthropic.claude-3-5-sonnet-20241022-v2:0` |

---

## Lo que hace cada codec con la URL base

```
LLM_URL=https://api.cerebras.ai

OpenAiCodec    → https://api.cerebras.ai/v1/chat/completions
AnthropicCodec → https://api.anthropic.com/v1/messages
GeminiCodec    → https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash:generateContent
BedrockCodec   → https://bedrock-runtime.us-east-1.amazonaws.com/model/anthropic.claude.../invoke
```

---

## Compatibilidad de URL

El codec acepta 3 variantes de `LLM_URL` para el formato OpenAI:

| `LLM_URL` | URL real usada | Estado |
|---|---|---|
| `https://api.cerebras.ai` | `.../v1/chat/completions` | ✅ Recomendado |
| `https://api.cerebras.ai/v1` | `.../v1/chat/completions` | ✅ Soportado |
| `https://api.cerebras.ai/v1/chat/completions` | `.../v1/chat/completions` | ✅ Retrocompat |
