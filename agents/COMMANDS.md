# COMMANDS.md

Lista de comandos operativos previstos para EtherBrain.

## Objetivo

Reducir la ambiguedad de ejecucion para agentes y humanos.

## Setup

```bash
./mvnw -v
java -version
```

## Desarrollo

```bash
./mvnw compile
./mvnw install -DskipTests
cd ether-brain-transport-cli && ./mvnw exec:java -Dexec.args="What time is it?"
```

## Tests

```bash
./mvnw test
./mvnw -pl ether-brain-core -Dtest=AgentLoopTest test
```

## Calidad

```bash
./mvnw verify
```

## Build

```bash
./mvnw clean package
```
