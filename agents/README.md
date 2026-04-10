# Agents Context

Indice principal del contexto operativo de EtherBrain.

## Como usar esta carpeta

Si eres un agente o una persona entrando por primera vez:

1. Lee este archivo.
2. Abre solo el documento que corresponde a tu tarea.
3. Si la tarea supera la spec activa, crea una spec separada en
   `specs/`.

## Documentos base

- [`PRODUCT.md`](./PRODUCT.md):
  problema, usuarios, objetivos y limites del runtime.
- [`ROADMAP.md`](./ROADMAP.md):
  direccion por fases para el runtime y sus extensiones.
- [`ARCHITECTURE.md`](./ARCHITECTURE.md):
  arquitectura hexagonal, puertos, adaptadores y flujo central.
- [`STACK.md`](./STACK.md):
  base tecnologica, versiones y restricciones.
- [`CONVENTIONS.md`](./CONVENTIONS.md):
  reglas de modelado, paquetes, testing y documentacion.
- [`COMMANDS.md`](./COMMANDS.md):
  comandos de setup, build, test y ejecucion.
- [`DECISIONS.md`](./DECISIONS.md):
  decisiones persistentes del proyecto.
- [`SPEC.md`](./SPEC.md):
  spec activa del runtime v0.
- [`specs/README.md`](./specs/README.md):
  indice de specs futuras por iniciativa.

## Regla de ownership documental

- Producto y direccion: `PRODUCT.md` y `ROADMAP.md`
- Sistema y restricciones: `ARCHITECTURE.md`, `STACK.md`,
  `CONVENTIONS.md`, `COMMANDS.md`
- Ejecucion actual: `SPEC.md`
- Memoria de decisiones: `DECISIONS.md`
