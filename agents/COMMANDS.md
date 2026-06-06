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
export LLM_URL=https://api.anthropic.com
export LLM_TOKEN=sk-ant-...
export LLM_MODEL=claude-opus-4-5

cd ether-brain/
./mvnw -pl ether-brain-transport-cli exec:java -Dexec.args="Quien eres?"
```

## Ejecutar con Groq

```bash
export LLM_TYPE=openai
export LLM_URL=https://api.groq.com/openai
export LLM_TOKEN=gsk_...
export LLM_MODEL=llama-3.3-70b-versatile

cd ether-brain/
./mvnw -pl ether-brain-transport-cli exec:java -Dexec.args="Quien eres?"
```

## Ejecutar con OpenRouter (enruta a cualquier modelo)

```bash
export LLM_TYPE=openai
export LLM_URL=https://openrouter.ai
export LLM_TOKEN=sk-or-...
export LLM_MODEL=anthropic/claude-opus-4-5   # ver openrouter.ai/models

cd ether-brain/
./mvnw -pl ether-brain-transport-cli exec:java -Dexec.args="Quien eres?"
```

## Ejecutar con Ollama local (sin token)

```bash
export LLM_TYPE=openai
export LLM_URL=http://localhost:11434
export LLM_MODEL=llama3.2

cd ether-brain/
./mvnw -pl ether-brain-transport-cli exec:java -Dexec.args="Quien eres?"
```

## Ejecutar con Google Gemini

```bash
export LLM_TYPE=gemini
export LLM_URL=https://generativelanguage.googleapis.com
export LLM_TOKEN=YOUR_GOOGLE_API_KEY
export LLM_MODEL=gemini-2.0-flash

cd ether-brain/
./mvnw -pl ether-brain-transport-cli exec:java -Dexec.args="Quien eres?"
```

## Ejecutar con AWS Bedrock

```bash
export LLM_TYPE=bedrock
export LLM_URL=https://bedrock-runtime.us-east-1.amazonaws.com
export LLM_MODEL=anthropic.claude-opus-4-5-20250514-v1:12000

cd ether-brain/
./mvnw -pl ether-brain-transport-cli exec:java -Dexec.args="Quien eres?"
```

**Nota:** Bedrock requiere que el cliente HTTP tenga SigV4 signing pre-configurado.
Consulta [AWS SDK for Java](https://docs.aws.amazon.com/sdk-for-java/) para configurar credenciales.

## REPL interactivo con sesion persistente

```bash
export LLM_TYPE=openai
export LLM_URL=https://api.cerebras.ai
export LLM_TOKEN=csk-...
export LLM_MODEL=gpt-oss-120b
export SESSION_DIR=/tmp/etherbrain-sessions

cd ether-brain/
./mvnw -pl ether-brain-transport-cli exec:java -Dexec.args="--session mi-sesion"
```

## Servidor HTTP (Jetty)

```bash
export LLM_TYPE=openai
export LLM_URL=https://api.cerebras.ai
export LLM_TOKEN=csk-...
export LLM_MODEL=gpt-oss-120b
export HTTP_PORT=8080
export AUTH_TOKEN=mi-token-secreto

java -jar ether-brain-transport-http/target/ether-brain-http.jar

# Llamar al agente
curl -X POST http://localhost:8080/sessions/s1/run \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer mi-token-secreto" \
  -d '{"message":"Hola"}'
```

## Bridge MQTT

```bash
export LLM_TYPE=openai
export LLM_URL=https://api.cerebras.ai
export LLM_TOKEN=csk-...
export LLM_MODEL=gpt-oss-120b
export MQTT_BROKER_URL=tcp://localhost:1883
export MQTT_REQUEST_TOPIC=agent/requests
export MQTT_RESPONSE_TOPIC=agent/responses

java -jar ether-brain-transport-mqtt/target/ether-brain-mqtt.jar

# Enviar mensaje (requiere mosquitto-clients)
mosquitto_pub -t agent/requests -m '{"session_id":"s1","message":"Hola"}'
# Recibir respuesta
mosquitto_sub -t "agent/responses/#" -v
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
