# PRODUCT.md

Fuente de verdad del producto EtherBrain.

## Problema

Los frameworks de agentes suelen imponer demasiada abstraccion desde el
dia uno. Eso dificulta entender el loop real de ejecucion, introduce
dependencias pesadas y complica la integracion con sistemas backend en
Java.

EtherBrain busca resolver esa friccion construyendo un runtime propio,
simple y extensible, donde cada paso del agente sea visible, tipado y
controlable.

## Usuarios

- Desarrollador backend Java:
  necesita un runtime entendible, integrable y mantenible sin adoptar un
  framework complejo.
- Arquitecto de software:
  necesita puertos claros para conectar modelos, tools, memoria y
  politicas sin acoplar el dominio a un proveedor.
- Equipo de plataforma:
  necesita observabilidad, control de riesgos y facilidad para endurecer
  el runtime con reglas operativas.

## Objetivos

- Construir un runtime v0 de un solo agente con loop determinista.
- Separar dominio, puertos y adaptadores bajo arquitectura hexagonal.
- Soportar tool calling, contexto de sesion y politicas basicas desde el
  inicio.
- Mantener el proyecto en Java 21 con biblioteca estandar como opcion
  por defecto.

## Metricas de exito

- Una conversacion puede iterar entre modelo y tools hasta devolver una
  respuesta final.
- El runtime puede cambiar de proveedor de modelo sin tocar el nucleo.
- El codigo base permanece pequeno, legible y testeable.

## No objetivos

- Construir multiagente desde la primera version.
- Incluir memoria vectorial o RAG en v0.
- Soportar MCP, shell arbitrario o streaming complejo en el arranque.

## Valor diferencial

EtherBrain apuesta por control explicito, contratos pequenos y
compatibilidad natural con ecosistemas enterprise Java. Su ventaja no es
tener mas features al inicio, sino una base mas clara para crecer.
