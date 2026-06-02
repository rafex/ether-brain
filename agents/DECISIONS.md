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

### DEC-0009 - Tres variables de entorno universales para el LLM

- Fecha: 2026-05-30
- Estado: accepted
- Contexto: el bootstrap tenia MODEL_PROVIDER con un switch y variables
  distintas por proveedor (ANTHROPIC_API_KEY, OPENAI_API_KEY, GROQ_API_KEY,
  etc.). Cada nuevo proveedor requeria un case nuevo. Era complejidad
  artificial porque el codec se puede deducir de la URL.
- Decision: reemplazar todas las variables de proveedor por tres universales:
  LLM_URL (endpoint), LLM_TOKEN (api key) y LLM_MODEL (nombre del modelo).
  El codec se detecta de la URL: si contiene "anthropic.com" se usa
  AnthropicCodec, cualquier otra URL usa OpenAiCodec.
- Consecuencias: agregar un nuevo proveedor OpenAI-compatible requiere cero
  cambios de codigo — solo cambiar LLM_URL. La configuracion es identica
  para Groq, Deepseek, Mistral, OpenRouter, Ollama o cualquier servidor local.
- Reemplaza: `none`

### DEC-0008 - HttpModelConfig con extraHeaders y maxTokens configurable

- Fecha: 2026-05-30
- Estado: accepted
- Contexto: algunos proveedores requieren headers HTTP adicionales
  (OpenRouter necesita HTTP-Referer/X-Title; Anthropic usa anthropic-beta
  para features experimentales). El maxTokens fijo en 1024 era demasiado
  pequeno para respuestas reales.
- Decision: agregar `extraHeaders: Map<String,String>` a `HttpModelConfig`
  con constructor backward-compatible y metodos fluidos `withExtraHeaders()`
  y `withMaxTokens()`. Subir default de maxTokens a 4096.
- Consecuencias: cualquier header proveedor-especifico se configura sin
  tocar los codecs ni el dominio.
- Reemplaza: `none`

### DEC-0007 - Un codec por formato de API, no por proveedor

- Fecha: 2026-05-30
- Estado: accepted
- Contexto: el mercado de LLMs tiene docenas de proveedores pero exactamente
  4 formatos de API distintos:
    openai     — el estandar de facto adoptado por la mayoria
    anthropic  — formato propio de Claude
    gemini     — formato propio de Google Gemini
    bedrock    — wrapping de AWS con firma SigV4
- Decision: un codec por formato, identificado por `LLM_TYPE`. Se han
  implementado los 4 codecs: `openai` (OpenAiCodec), `anthropic` (AnthropicCodec),
  `gemini` (GeminiCodec), y `bedrock` (BedrockCodec).
- Consecuencias: agregar cualquier proveedor OpenAI-compatible requiere
  cero cambios de codigo. Solo se necesita un codec nuevo cuando el formato
  de la API es genuinamente distinto (Gemini, Bedrock).
- Reemplaza: `none`

### DEC-0006 - Jackson en modulos infra HTTP y file

- Fecha: 2026-05-29
- Estado: accepted
- Contexto: EtherBrain necesita serializar/deserializar JSON para llamar
  a APIs de proveedores LLM y persistir sesiones en archivos. La
  biblioteca estandar de Java no incluye un parser JSON.
- Decision: agregar `jackson-databind` como dependencia en
  `ether-brain-infra-http` y `ether-brain-infra-file`. No se introduce
  en `ports`, `core` ni `common`, preservando el dominio libre de
  dependencias externas.
- Consecuencias: el dominio sigue limpio; los adaptadores de
  infraestructura pueden evolucionar la serializacion sin tocar el loop.
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
