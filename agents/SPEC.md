# SPEC.md

Spec activa del runtime base de EtherBrain.

## Resumen

Construir una primera version del runtime de agentes IA en Java 21,
centrada en un solo agente, tool calling local, estado de sesion en
memoria y politicas minimas de control.

## Problema

Hoy no existe una base propia para ejecutar agentes con control completo
del flujo, contratos tipados y bajo acoplamiento. Empezar por frameworks
externos introduciria complejidad antes de validar el loop central.

## Objetivo

Tener un runtime capaz de recibir una conversacion, construir el prompt,
invocar un modelo, ejecutar una tool cuando corresponda y regresar una
respuesta final dentro de limites definidos.

## Alcance

- Incluye:
  contratos de dominio, loop principal, tools locales, memoria en
  memoria, politicas basicas, trazas y puertos paralelos para futura
  expansion de recursos y prompts.
- Excluye:
  multiagente, memoria persistente avanzada, MCP, RAG y streaming.

## Requisitos funcionales

- RF-1: El runtime debe aceptar mensajes de usuario y estado de sesion.
- RF-2: El runtime debe construir una solicitud al modelo usando
  instrucciones, historial y tools disponibles.
- RF-3: El modelo debe poder devolver respuesta final o solicitud de
  tool.
- RF-4: El runtime debe resolver la tool solicitada mediante un registro
  y un ejecutor.
- RF-4.1: El runtime debe preservar `ToolRegistry` como contrato estable
  para tools, aun si en el futuro mezcla fuentes locales y remotas.
- RF-5: El resultado de la tool debe incorporarse al estado y permitir
  una nueva iteracion.
- RF-6: El runtime debe terminar por respuesta final o politica de corte.

## Requisitos no funcionales

- RNF-1: El nucleo debe permanecer desacoplado de proveedores concretos.
- RNF-2: La version inicial debe poder implementarse con biblioteca
  estandar de Java.
- RNF-3: El parseo de respuesta del modelo debe ser simple y robusto para
  un MVP.
- RNF-4: Cada paso debe ser observable mediante logs o trazas basicas.

## Criterios de aceptacion

- Dado un `FakeModelClient` que primero pide una tool y luego responde,
  cuando corre `AgentLoop`, entonces el runtime ejecuta la tool y retorna
  la respuesta final.
- Dado un modelo que nunca termina, cuando supera el maximo de pasos,
  entonces el runtime falla con una politica explicita.
- Dado una tool inexistente, cuando el modelo la solicita, entonces el
  runtime responde con un error controlado y trazable.

## Dependencias y riesgos

- Dependencia: definicion estable de contratos como `ModelClient`,
  `Tool`, `ToolResult` y `ConversationState`.
- Dependencia: separar registros de tools, recursos y prompts sin
  contaminar el loop principal antes de integrar fuentes remotas.
- Riesgo: sobrecomplicar el parseo de salida demasiado pronto.
- Riesgo: mezclar el dominio con detalles HTTP del proveedor.

## Plan de validacion

- Test automatizado:
  pruebas del loop con `FakeModelClient` y tools de prueba.
- Test manual:
  ejecucion por CLI con una conversacion simple y tools `echo` o
  `current_time`.
- Evidencia esperada:
  logs por paso, historial actualizado y respuesta final observable.
