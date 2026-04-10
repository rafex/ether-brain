# EtherBrain

Runtime de agentes IA construido en Java 21 con biblioteca estandar y
arquitectura hexagonal.

El objetivo del proyecto es crear un runtime minimo, claro y extensible
para orquestar sesiones, prompts, llamadas a modelo y ejecucion de tools
sin depender de frameworks pesados.

## Principios

- El runtime debe entenderse leyendo contratos pequenos y explicitos.
- La arquitectura favorece puertos y adaptadores sobre acoplamiento a
  proveedores externos.
- La primera meta es un loop confiable, no un sistema multiagente.
- Las dependencias externas se agregan solo cuando el runtime base ya
  funciona de forma observable.

## Estructura

- [`AGENTS.md`](./AGENTS.md):
  reglas de trabajo para agentes dentro de EtherBrain.
- [`agents/README.md`](./agents/README.md):
  indice del contexto operativo y documental del proyecto.
- `ether-brain-*`:
  modulos Maven del runtime hexagonal, siguiendo el patron de puertos,
  adaptadores, bootstrap y pruebas de arquitectura.

## Alcance actual

- Disenar el runtime base de un solo agente.
- Definir contratos para modelo, tools, memoria de sesion y politicas.
- Preparar la evolucion hacia persistencia, handoffs y mas adaptadores.
- Mantener un scaffold multi-modulo que preserve la arquitectura desde
  la primera linea de codigo.

## Flujo recomendado

1. Leer [`agents/PRODUCT.md`](./agents/PRODUCT.md) para entender el
   problema y los objetivos.
2. Revisar [`agents/ARCHITECTURE.md`](./agents/ARCHITECTURE.md),
   [`agents/STACK.md`](./agents/STACK.md) y
   [`agents/CONVENTIONS.md`](./agents/CONVENTIONS.md) antes de definir
   estructura o codigo.
3. Usar [`agents/SPEC.md`](./agents/SPEC.md) como spec activa del runtime
   inicial y crear specs separadas en `agents/specs/` cuando el alcance
   crezca.
4. Registrar decisiones permanentes en
   [`agents/DECISIONS.md`](./agents/DECISIONS.md).

## Regla de lectura

1. Entrar por el `README.md` de la carpeta actual.
2. Leer solo el contexto necesario para la tarea.
3. Actualizar la fuente de verdad correspondiente si cambian supuestos.
