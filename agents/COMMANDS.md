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
export MODEL_PROVIDER=anthropic
export ANTHROPIC_API_KEY=sk-ant-...
export MODEL_NAME=claude-opus-4-5

cd ether-brain/
./mvnw -pl ether-brain-transport-cli exec:java -Dexec.args="Quien eres?"
```

## Ejecutar con OpenAI

```bash
export MODEL_PROVIDER=openai
export OPENAI_API_KEY=sk-...
export MODEL_NAME=gpt-4o-mini

cd ether-brain/
./mvnw -pl ether-brain-transport-cli exec:java -Dexec.args="Quien eres?"
```

## REPL interactivo con sesion persistente

```bash
export MODEL_PROVIDER=anthropic
export ANTHROPIC_API_KEY=sk-ant-...
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
