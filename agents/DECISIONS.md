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

### DEC-0003 - Scaffold multi-modulo alineado a referencia hexagonal

- Fecha: 2026-04-10
- Estado: accepted
- Contexto: EtherBrain necesita preservar fronteras claras entre
  dominio, puertos, adaptadores y bootstrap desde v0, tomando como
  referencia estructural `ether-archetype` sin heredar infraestructura
  que aun no aplica al runtime.
- Decision: organizar el codigo en modulos Maven separados para
  `common`, `ports`, `core`, `infra-memory`, `tools-local`,
  `bootstrap`, `transport-cli` y `architecture-tests`.
- Consecuencias: aumenta el numero de modulos desde el inicio, pero deja
  la arquitectura verificable, facilita DI manual y reduce el riesgo de
  mezclar el loop del agente con adaptadores concretos.
- Reemplaza: `none`

### DEC-0004 - Logging estandarizado sobre ether-logging-core

- Fecha: 2026-04-10
- Estado: accepted
- Contexto: EtherBrain necesita trazas basicas desde v0, pero sin meter
  frameworks de logging que oculten la configuracion o introduzcan
  complejidad innecesaria en el runtime.
- Decision: usar `ether-logging-core` como capa ligera sobre
  `java.util.logging` para configuracion programatica y mensajes
  consistentes.
- Consecuencias: el runtime mantiene logging estandar de JVM, pero con
  una API comun del ecosistema Ether que facilita evolucion futura hacia
  mejor observabilidad.
- Reemplaza: `none`

### DEC-0005 - ToolRegistry se preserva y se compone

- Fecha: 2026-04-10
- Estado: accepted
- Contexto: EtherBrain necesita crecer hacia fuentes remotas de
  capacidades como MCP sin reescribir el loop del agente ni forzar a
  `ToolRegistry` a modelar recursos y prompts.
- Decision: mantener `ToolRegistry` como fachada estable para tools,
  introducir `CompositeToolRegistry` para mezclar varias fuentes y crear
  registros hermanos `ResourceRegistry` y `PromptRegistry` para
  capacidades no invocables.
- Consecuencias: el loop principal sigue intacto, mientras la
  arquitectura queda preparada para integrar MCP como proveedor de
  registros en vez de acoplar el protocolo al nucleo.
- Reemplaza: `none`
