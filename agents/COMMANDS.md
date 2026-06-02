# COMMANDS.md

Comandos operativos de EtherBrain. Para guia completa ver `agents/OPERATIONS.md`.

## Setup

```bash
./mvnw -v      # verifica Maven wrapper
java -version  # debe ser Java 21
```

## Compilar

```bash
cd ether-brain/
./mvnw clean install -DskipTests
```

## Ejecutar (demo sin LLM)

```bash
cd ether-brain/
./mvnw -pl ether-brain-transport-cli exec:java -Dexec.args="What time is it?"
```

## Ejecutar con Anthropic

```bash
export LLM_TYPE=anthropic
export LLM_URL=https://api.anthropic.com/v1/messages
export LLM_TOKEN=sk-ant-...
export LLM_MODEL=claude-opus-4-5

cd ether-brain/
./mvnw -pl ether-brain-transport-cli exec:java -Dexec.args="Quien eres?"
```

## Ejecutar con Groq

```bash
export LLM_TYPE=openai
export LLM_URL=https://api.groq.com/openai/v1/chat/completions
export LLM_TOKEN=gsk_...
export LLM_MODEL=llama-3.3-70b-versatile

cd ether-brain/
./mvnw -pl ether-brain-transport-cli exec:java -Dexec.args="Quien eres?"
```

## Ejecutar con OpenRouter (enruta a cualquier modelo)

```bash
export LLM_TYPE=openai
export LLM_URL=https://openrouter.ai/api/v1/chat/completions
export LLM_TOKEN=sk-or-...
export LLM_MODEL=anthropic/claude-opus-4-5   # ver openrouter.ai/models

cd ether-brain/
./mvnw -pl ether-brain-transport-cli exec:java -Dexec.args="Quien eres?"
```

## Ejecutar con Ollama local (sin token)

```bash
export LLM_TYPE=openai
export LLM_URL=http://localhost:11434/v1/chat/completions
export LLM_MODEL=llama3.2

cd ether-brain/
./mvnw -pl ether-brain-transport-cli exec:java -Dexec.args="Quien eres?"
```

## Ejecutar con Google Gemini

```bash
export LLM_TYPE=gemini
export LLM_URL=https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash:generateContent
export LLM_TOKEN=YOUR_GOOGLE_API_KEY
export LLM_MODEL=gemini-2.0-flash

cd ether-brain/
./mvnw -pl ether-brain-transport-cli exec:java -Dexec.args="Quien eres?"
```

## Ejecutar con AWS Bedrock

```bash
export LLM_TYPE=bedrock
export LLM_URL=https://bedrock-runtime.us-east-1.amazonaws.com/model/anthropic.claude-opus-4-5-20250514-v1:12000/invoke
export LLM_MODEL=anthropic.claude-opus-4-5-20250514-v1:12000

cd ether-brain/
./mvnw -pl ether-brain-transport-cli exec:java -Dexec.args="Quien eres?"
```

**Nota:** Bedrock requiere que el cliente HTTP tenga SigV4 signing pre-configurado.
Consulta [AWS SDK for Java](https://docs.aws.amazon.com/sdk-for-java/) para configurar credenciales.

## REPL interactivo con sesion persistente

```bash
export LLM_TYPE=anthropic
export LLM_URL=https://api.anthropic.com/v1/messages
export LLM_TOKEN=sk-ant-...
export LLM_MODEL=claude-opus-4-5
export SESSION_DIR=/tmp/etherbrain-sessions

cd ether-brain/
./mvnw -pl ether-brain-transport-cli exec:java -Dexec.args="--session mi-sesion"
```

## Tests

```bash
cd ether-brain/
./mvnw test
./mvnw -pl ether-brain-core -Dtest=AgentLoopTest test
```

## Calidad

```bash
cd ether-brain/
./mvnw verify
```

## Build completo

```bash
cd ether-brain/
./mvnw clean package
```
