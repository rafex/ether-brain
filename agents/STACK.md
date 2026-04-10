# STACK.md

Fuente de verdad de la base tecnologica de EtherBrain.

## Runtime

- Lenguaje: Java
- Version obligatoria: 21
- Build tool preferido: Maven

## Biblioteca base

- Biblioteca estandar de Java:
  opcion por defecto para HTTP, concurrencia, logging y colecciones.
- `java.net.http.HttpClient`:
  cliente HTTP para adaptadores de modelo.
- `java.util.logging` + `ether-logging-core`:
  baseline de logging programatico y formateo consistente sin frameworks
  externos.

## Frameworks

- Ninguno en el nucleo:
  evitar frameworks pesados mientras el runtime base madura.
- Dependencias de test:
  se permiten cuando simplifican validacion, sin contaminar el dominio.

## Infraestructura

- Persistencia inicial:
  en memoria.
- Persistencia futura:
  archivo o base de datos detras de `SessionStore`.
- Observabilidad:
  logs estructurables y trazas por paso.
- CI/CD:
  Maven + pipeline de build y tests cuando el repo tenga codigo.

## Integraciones

- Proveedor LLM por HTTP:
  adaptador intercambiable detras de `ModelClient`.
- Tools locales:
  implementaciones Java controladas por `ToolRegistry` y politicas.

## Restricciones

- Mantener compatibilidad con Java 21.
- No introducir dependencias que oculten el loop principal sin una
  decision explicita.
- Evitar serializacion o parseo complejo en v0 si puede resolverse con
  un contrato de salida mas simple.
