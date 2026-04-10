# AGENTS.md

Este archivo define como deben operar los agentes dentro de EtherBrain.

## Regla principal

Antes de trabajar en cualquier carpeta, leer primero su `README.md`.

## Mapa rapido

- `README.md` del root: contexto general del runtime EtherBrain.
- `agents/README.md`: indice del contexto operativo del proyecto.
- `agents/SPEC.md`: spec activa del runtime base.
- `agents/specs/README.md`: indice para iniciativas futuras.

## Politica de contexto

- Los archivos en MAYUSCULAS son fuente de verdad para agentes.
- Los `README.md` enrutan; no reemplazan documentos de decision.
- Leer el minimo contexto suficiente para ejecutar bien la tarea.
- Si cambia una verdad compartida, actualizar el documento fuente.

## Flujo de trabajo recomendado

1. Leer el `README.md` de la carpeta actual.
2. Revisar `agents/PRODUCT.md` y `agents/ARCHITECTURE.md`.
3. Confirmar restricciones en `agents/STACK.md` y
   `agents/CONVENTIONS.md`.
4. Trabajar sobre `agents/SPEC.md` o crear una spec separada.
5. Registrar decisiones permanentes en `agents/DECISIONS.md`.

## Criterio de actualizacion

- `ROADMAP.md` cambia cuando cambia la direccion del runtime.
- `SPEC.md` cambia cuando cambia el alcance de la capacidad activa.
- `DECISIONS.md` cambia cuando se fija un tradeoff estructural.
