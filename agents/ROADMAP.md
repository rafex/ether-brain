# ROADMAP.md

Direccion del proyecto EtherBrain en el tiempo.

## Objetivo

Dar contexto de prioridad sin convertir esto en una lista de tickets.

## Ahora

- Definir contratos del runtime base: mensajes, requests, responses y
  tool calls.
- Implementar el loop de un solo agente con maximo de pasos.
- Crear tools locales de prueba y almacenamiento en memoria.
- Incorporar trazas simples y politicas minimas de seguridad.

## Despues

- Agregar adaptador HTTP real para uno o mas proveedores LLM.
- Introducir persistencia de sesion en archivo o base de datos.
- Mejorar la ventana de mensajes y estrategia de resumen.
- Formalizar pruebas de integracion del loop completo.

## Mas adelante

- Tool calling estructurado por proveedor.
- Streaming y observabilidad mas rica.
- Handoffs o subagentes sobre el mismo runtime base.
- Integraciones externas adicionales como MCP si el dominio ya lo pide.

## No hacer por ahora

- Multiagente como feature principal.
- Memoria vectorial o RAG antes de validar el loop.
- Shell arbitrario o ejecucion remota sin politicas fuertes.
