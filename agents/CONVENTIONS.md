# CONVENTIONS.md

Reglas operativas y de implementacion de EtherBrain.

## Codigo

- Modelar contratos pequenos con `record`, `enum` y `sealed interface`
  cuando ayuden a expresar el dominio.
- Separar paquetes por responsabilidad hexagonal: dominio, puertos y
  adaptadores.
- Preferir nombres explicitos como `ModelClient`, `ToolResult` o
  `ConversationState` frente a abstracciones vagas.
- Evitar logica de negocio en adaptadores.
- Mantener los cambios pequenos, locales y testeables.

## Estructura

- El nucleo del runtime no depende de proveedores concretos.
- Los adaptadores viven fuera del dominio y se conectan por interfaces.
- Las tools deben declararse con contrato estable: nombre, descripcion,
  esquema de entrada y resultado.

## Tests

- Cada caso de uso principal debe tener al menos una validacion de loop.
- Crear `FakeModelClient` antes de integrar un proveedor real.
- Probar errores de tool, maximo de pasos y timeouts como escenarios de
  comportamiento, no solo de infraestructura.

## Documentacion

- Los `README.md` indexan.
- Los archivos en MAYUSCULAS contienen contexto fuente.
- No duplicar decisiones entre `SPEC.md`, `ARCHITECTURE.md` y
  `DECISIONS.md`.

## Agentes

- Antes de editar, leer el `README.md` de la carpeta.
- Si un cambio altera arquitectura o tradeoffs, registrar la decision.
- Si una tarea supera la spec activa, abrir una spec separada en
  `agents/specs/`.
