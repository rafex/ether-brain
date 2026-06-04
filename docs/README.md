# EtherBrain — Documentación

Repositorio de documentación humana del proyecto EtherBrain.

## Índice

| Documento | Descripción |
|---|---|
| [integracion-llm.md](integracion-llm.md) | Primera integración con LLM real — resultados y evidencia |
| [arquitectura.md](arquitectura.md) | Visión general de la arquitectura hexagonal |
| [proveedores.md](proveedores.md) | Guía de proveedores LLM soportados y sus URLs base |
| [faiss-poc.md](faiss-poc.md) | RAG y memoria de agente con faiss-poc — configuración, upload de documentos, búsqueda semántica y memoria híbrida |
| [tools-externas.md](tools-externas.md) | Tools externas plug-and-play — expón cualquier CLI como tool del agente con un archivo JSON, sin código Java |
| [challenge-tools.md](challenge-tools.md) | Challenge de arquitectura — 7 mecanismos para dar tools al runtime, pros/contras, tecnologías y la filosofía de independencia |

## Relación con `agents/`

La carpeta `agents/` contiene instrucciones para agentes IA que trabajan en el repositorio
(decisiones de arquitectura, convenciones, roadmap).

Esta carpeta `docs/` contiene documentación para **humanos**: guías de uso, evidencia de
pruebas, decisiones explicadas en lenguaje natural, tutoriales.
