# COMMANDS.md

Lista de comandos operativos previstos para EtherBrain.

## Objetivo

Reducir la ambiguedad de ejecucion para agentes y humanos.

## Setup

```bash
mvn -v
java -version
```

## Desarrollo

```bash
mvn compile
mvn exec:java -Dexec.mainClass=com.etherbrain.Main
```

## Tests

```bash
mvn test
```

## Calidad

```bash
mvn verify
```

## Build

```bash
mvn clean package
```

## Utilidad

```bash
mvn -Dtest=AgentLoopTest test
mvn -DskipTests package
```
