# DECISIONS.md

Registro de decisiones persistentes de EtherBrain.

## Cuando registrar aqui

Registrar una decision cuando cambie:

- la arquitectura
- una convencion importante
- una tecnologia base
- un tradeoff que otros agentes deben respetar

## Decisiones

### DEC-0001 - Arquitectura hexagonal desde el inicio

- Fecha: 2026-04-10
- Estado: accepted
- Contexto: el runtime necesita crecer hacia nuevos proveedores de
  modelo, persistencia y herramientas sin acoplar el nucleo.
- Decision: organizar el proyecto con puertos y adaptadores, dejando el
  loop del agente y las politicas dentro del dominio.
- Consecuencias: aumenta la disciplina de diseno desde v0, pero evita
  que infraestructura y dominio se mezclen temprano.
- Reemplaza: `none`

### DEC-0002 - Java 21 y biblioteca estandar como baseline

- Fecha: 2026-04-10
- Estado: accepted
- Contexto: el objetivo del proyecto es entender y controlar el runtime
  sin depender de frameworks de agentes.
- Decision: usar Java 21 y priorizar biblioteca estandar para HTTP,
  logging y concurrencia. Las dependencias externas se evaluan despues de
  validar el loop base.
- Consecuencias: el MVP requerira contratos y parseo mas controlados,
  pero el sistema sera mas transparente y portable.
- Reemplaza: `none`
