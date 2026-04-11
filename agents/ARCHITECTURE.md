# ARCHITECTURE.md

Describe la arquitectura actual y objetivo de EtherBrain.

## Vision general

EtherBrain sigue una arquitectura hexagonal. El nucleo del dominio vive
aislado de proveedores de modelo, persistencia, CLI o integraciones
externas. El centro del sistema es el loop del agente, que construye el
contexto, consulta al modelo, decide si responder o ejecutar una tool y
repite hasta cumplir una condicion de corte.

La forma esperada del sistema es:

Usuario -> Session -> PromptBuilder -> ModelClient -> ModelResponse
-> ToolRegistry -> ToolExecutor -> ToolResult -> Session -> siguiente
iteracion

## Puertos del dominio

- `ModelClient`:
  puerto de salida para generar respuestas desde un proveedor de modelo.
- `SessionStore`:
  puerto de salida para recuperar y persistir estado conversacional.
- `ToolRegistry`:
  puerto de consulta para descubrir tools habilitadas.
- `ResourceRegistry`:
  puerto de consulta y lectura para contexto externo direccionable.
- `PromptRegistry`:
  puerto de consulta para prompts reutilizables y futuras integraciones
  remotas.
- `ToolExecutor`:
  puerto de salida para ejecutar una tool concreta con contexto.
- `PolicyEngine`:
  puerto de dominio para validar limites de seguridad y ejecucion.

## Casos de uso del nucleo

- `AgentRuntime`:
  fachada principal para iniciar una ejecucion.
- `AgentLoop`:
  orquesta el ciclo paso a paso y aplica condiciones de corte.
- `PromptBuilder`:
  transforma estado, instrucciones y tools disponibles en una solicitud
  al modelo.
- `ExecutionContext`:
  encapsula sesion, configuracion, trazas y politicas activas.

## Adaptadores esperados

- Entrada:
  CLI, API HTTP o pruebas de integracion.
- Salida:
  cliente HTTP para proveedores LLM, almacenamiento en memoria o archivo,
  logger, tools locales y futuras fuentes remotas de capacidades.

## Flujo principal

1. Un adaptador de entrada crea o carga una sesion.
2. `AgentRuntime` construye un `ExecutionContext`.
3. `AgentLoop` arma un `ModelRequest` con `PromptBuilder`.
4. `ModelClient` devuelve una respuesta final o una solicitud de tool.
5. Si hay tool call, `ToolExecutor` ejecuta la tool y guarda su resultado
   en la conversacion.
6. El loop continua hasta respuesta final, maximo de pasos o timeout.

## Reglas de dependencia

- El dominio no depende de adaptadores concretos.
- Los adaptadores implementan puertos definidos por el dominio.
- Las tools no deben acceder directamente a infraestructura fuera de su
  adaptador sin pasar por politicas.
- Los formatos de respuesta del proveedor no deben filtrarse al dominio.
- Integraciones como MCP deben entrar mediante registros o proveedores
  especificos, no acopladas al `AgentLoop`.

## Riesgos actuales

- Parseo de salida del modelo demasiado fragil en v0.
  Mitigacion: usar un contrato de salida controlado y facil de validar.
- Crecimiento accidental del historial de conversacion.
  Mitigacion: aplicar `MessageWindow` y politicas de tamano.
- Acoplamiento prematuro a un proveedor LLM.
  Mitigacion: mantener `ModelClient` como puerto estable.
