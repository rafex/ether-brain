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

### DEC-0010 - Jetty 12 reemplaza com.sun.net.httpserver

- Fecha: 2026-06-03
- Estado: accepted
- Contexto: `com.sun.net.httpserver` no soporta TLS/SNI nativo, HTTP/2
  ni configuracion avanzada de threading. Para produccion se necesita
  un servidor embebido real.
- Decision: usar Jetty 12.1.10 (`jetty-server`, `jetty-servlet`) en
  `ether-brain-transport-http`. Solo en ese modulo — no entra en el
  dominio ni en bootstrap.
- Consecuencias: el transporte HTTP soporta HTTPS, rate limiting y
  headers de seguridad sin codigo adicional de bajo nivel.
- Reemplaza: `none`

### DEC-0011 - MQTT transport via Eclipse Paho

- Fecha: 2026-06-03
- Estado: accepted
- Contexto: el runtime necesita un canal de entrada asincronico para
  sistemas IoT, pipelines de eventos y arquitecturas event-driven sin
  exponer HTTP.
- Decision: nuevo modulo `ether-brain-transport-mqtt` con Eclipse Paho
  v3 (`org.eclipse.paho.client.mqttv3:1.2.5`). Compatible con
  Mosquitto y cualquier broker MQTT 3.1/3.1.1. Un virtual thread por
  mensaje. Fat jar independiente.
- Consecuencias: el runtime puede recibir mensajes de cualquier broker
  MQTT sin cambiar el dominio. Los tests no necesitan broker real —
  `RecordingBridge` sobreescribe `publish()` para capturar mensajes.
- Reemplaza: `none`

### DEC-0012 - LLM_TEMPERATURE configurable por variable de entorno

- Fecha: 2026-06-03
- Estado: accepted
- Contexto: `GeminiCodec` tenia `temperature: 0.7` hardcodeado. Los
  otros codecs no exponían temperatura. Diferentes casos de uso
  necesitan control de temperatura sin recompilar.
- Decision: `LLM_TEMPERATURE` como variable de entorno. `HttpModelConfig`
  incorpora `temperature` como componente con sentinel `TEMPERATURE_UNSET
  = -1.0`. Cada codec emite el campo solo cuando `temperature >= 0`.
  `GeminiCodec` hace fallback a `0.7` cuando no se define.
- Consecuencias: backward-compat total — constructores existentes de
  5 y 6 argumentos pasan `-1.0` automaticamente. Ningun codec cambia
  su comportamiento por defecto.
- Reemplaza: `none`

### DEC-0013 - MetricsCollector como puerto del dominio

- Fecha: 2026-06-03
- Estado: accepted
- Contexto: el runtime necesita observabilidad de metricas (latencia
  de LLM, requests HTTP, mensajes MQTT) sin acoplar el dominio a
  Micrometer o cualquier framework de metricas.
- Decision: `MetricsCollector` como puerto en `ether-brain-ports`.
  `LoggingMetricsCollector` en `ether-brain-core` emite via JUL.
  `noop()` como singleton sin efecto. Controlado por `METRICS_ENABLED`.
- Consecuencias: cambiar a Micrometer o OpenTelemetry es un adaptador
  nuevo — el dominio no cambia. Los tests usan noop o captura de logs.
- Reemplaza: `none`

### DEC-0014 - ArchUnit para verificacion de fronteras hexagonales

- Fecha: 2026-06-03
- Estado: accepted
- Contexto: con 12 modulos Maven el riesgo de introducir dependencias
  cruzadas accidentales es alto. La revision manual no escala.
- Decision: 6 reglas ArchUnit en `ether-brain-architecture-tests`
  verificadas en cada build: aislamiento de core, puros de ports,
  bootstrap sin transportes, y cada transporte independiente de los
  otros.
- Consecuencias: cualquier violacion de la arquitectura hexagonal
  falla en CI antes de merge.
- Reemplaza: regla unica anterior que solo verificaba core vs infra.

### DEC-0015 - Seguridad HTTP en capa de transporte

- Fecha: 2026-06-03
- Estado: accepted
- Contexto: el transporte HTTP quedaba expuesto sin autenticacion,
  sin limite de peticiones y sin proteccion contra SSRF en callbacks.
- Decision: en `ether-brain-transport-http`: autenticacion Bearer via
  `AUTH_TOKEN`, rate limiting configurable via `HTTP_RATE_LIMIT_RPS`,
  validacion de `callback_url` contra lista de hosts permitidos (guard
  SSRF), limite de body size via `HTTP_MAX_BODY_BYTES`. Todo opcional
  por variable de entorno para no romper entornos de desarrollo.
- Consecuencias: deployment en produccion requiere solo definir
  `AUTH_TOKEN` y `HTTP_RATE_LIMIT_RPS`.
- Reemplaza: `none`

### DEC-0016 - logging.properties en classpath para silenciar terceros

- Fecha: 2026-06-03
- Estado: accepted
- Contexto: Jetty 12, Eclipse Paho y AWS SDK emiten muchos mensajes
  de INFO/FINE que no son utiles para el runtime y contaminan los logs.
  `LOG_LEVEL` afecta al root logger y no puede silenciar paquetes
  especificos sin sobreescribir todo.
- Decision: `logging.properties` en el classpath de `ether-brain-bootstrap`
  que fija Jetty, Paho, Jackson y AWS SDK a `WARNING`. Se carga antes
  de `configureRootLogger()` para que los niveles por paquete no
  sean sobreescritos por el root.
- Consecuencias: los logs del runtime son limpios por defecto. Para
  depurar Paho o Jetty basta descombentar las lineas en
  `logging.properties`.
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
