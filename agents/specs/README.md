# Specs Index

Indice de iniciativas o capacidades futuras definidas como specs
separadas.

## Cuando crear una carpeta o archivo aqui

Crear una spec separada cuando:

- el cambio supera una sola sesion de trabajo
- participan varias areas del runtime
- una iniciativa necesita su propio historial de decisiones
- la `SPEC.md` raiz ya no alcanza

## Iniciativas probables

- `provider-http/`:
  adaptador real para proveedores LLM por HTTP.
- `session-persistence/`:
  persistencia de conversaciones fuera de memoria.
- `tool-sandboxing/`:
  politicas y limites de seguridad para tools.
- `handoffs/`:
  coordinacion entre mas de un agente o subflujo.

## Estructura sugerida

```text
agents/specs/
  mi-iniciativa/
    README.md
    SPEC.md
```

## Regla de navegacion

- Si entras a una iniciativa, abre primero su `README.md`.
- Si no existe `README.md`, abre su `SPEC.md`.
- Mantener los nombres de carpetas en kebab-case.
