# Diagnóstico del Proyecto

_Fecha: 2026-05-20 | Repositorio: ether-brain_

---

## 1. Exploración

### Estructura general

Monorepo Java 21 multi-módulo Maven con arquitectura hexagonal. El código fuente vive bajo `ether-brain/` con 8 submódulos organizados por capa arquitectónica:

```
ether-brain/
├── ether-brain-ports/          ← interfaces del dominio (puertos)
├── ether-brain-core/           ← lógica central (AgentLoop, PromptBuilder, etc.)
├── ether-brain-common/         ← excepciones compartidas
├── ether-brain-infra-memory/   ← adaptador de sesión en memoria
├── ether-brain-tools-local/    ← tools locales (Echo, CurrentTime)
├── ether-brain-bootstrap/      ← wiring de dependencias + demo
├── ether-brain-transport-cli/  ← entrada CLI (Main.java)
├── ether-brain-architecture-tests/ ← tests de arquitectura (ArchUnit)
└── pom.xml                     ← parent POM multi-módulo
```

Directorios de soporte: `agents/` (documentación SpecNative), `.opencode/` (operativo de opencode).

### Lenguajes y tecnologías

- **Java 21** (lenguaje principal, 39 archivos `.java`, 919 líneas)
- **Maven** (build system, 9 archivos `pom.xml`)
- **Markdown** (documentación SpecNative, 12 archivos)
- **JUnit Jupiter 5.12.2** (framework de tests)
- **ArchUnit 1.4.1** (tests de arquitectura)

### Sistema de build / dependencias

Maven multi-módulo con `ether-brain/pom.xml` como parent. Depende de un parent POM externo: `dev.rafex.ether.parent:ether-parent:9.5.5-SNAPSHOT` (requiere repositorio `ether-parent` en disco).

Propiedades clave:
- `java.version=21`, `maven.compiler.release=21`
- Plugins configurados: `maven-compiler-plugin:3.14.0`, `maven-surefire-plugin:3.5.3`, `maven-source-plugin`, `maven-javadoc-plugin`, `flatten-maven-plugin`, `exec-maven-plugin:3.5.0`
- Wrapper Maven incluido (`mvnw`, `mvnw.cmd`)

### Puntos de entrada

- `Main.main()` en `ether-brain-transport-cli` → entrada CLI del runtime
- `ApplicationBootstrap.bootstrapForDemo()` → wiring de dependencias con `DemoModelClient` hardcodeado
- `AgentRuntime.run(sessionId, userMessage)` → fachada principal del runtime

### Módulos y componentes clave

| Módulo | Rol | Clases principales |
|--------|-----|-------------------|
| `ports` | Interfaces del dominio (puertos hexagonales) | `ModelClient`, `Tool`, `ToolRegistry`, `ToolExecutor`, `SessionStore`, `PolicyEngine`, `AgentConfig`, `ExecutionContext`, `ModelResponse` (sealed), `FinalAnswer`, `ToolRequest` |
| `core` | Lógica de negocio | `AgentLoop`, `AgentRuntime`, `PromptBuilder`, `DefaultPolicyEngine`, `DefaultToolExecutor` |
| `common` | Excepciones | `AgentException`, `ToolExecutionException` |
| `infra-memory` | Adaptador de sesión | `InMemorySessionStore` |
| `tools-local` | Implementaciones de tools | `EchoTool`, `CurrentTimeTool`, `InMemoryToolRegistry`, `CompositeToolRegistry` |
| `bootstrap` | Wiring y demo | `ApplicationBootstrap`, `DemoModelClient` (inner class) |
| `transport-cli` | Entrada | `Main` |
| `architecture-tests` | Validación estructural | `HexagonalArchitectureTest` |

### Archivos de configuración relevantes

- `.gitignore` — bien configurado: ignora `target/`, `.opencode/worktrees/`, `.idea/`, `*.log`, etc.
- `ether-brain/pom.xml` — parent POM del monorepo con 8 módulos
- `agents/PRODUCT.md`, `agents/ARCHITECTURE.md`, `agents/STACK.md`, `agents/DECISIONS.md` — documentación SpecNative
- `LICENSE` — MIT
- **Sin Dockerfile**, **sin CI/CD** (GitHub Actions, GitLab CI, etc.)

### Estado del repositorio

- **Rama actual:** `main`
- **Ramas remotas:** `origin/main`
- **Último commit:** `1f860c1 fix(publish): align groupId with ether namespace`
- **Cambios pendientes:** `.gitignore` modificado (sin commitear)
- **Archivos sin trackear:** ninguno
- **Worktrees activos:** ninguno
- **Commits totales:** 15 | **Contribuidores:** 2 | **Antigüedad:** 37 días

---

## 2. Revisión de calidad

### Problemas estructurales o de diseño

1. **DemoModelClient hardcodeado en bootstrap** — `ApplicationBootstrap` contiene una inner class `DemoModelClient` que simula un LLM con reglas `if/else`. No existe un adaptador real de proveedor de modelo (HTTP, OpenAI, etc.). El runtime no puede conectarse a un LLM real.
2. **Puertos sin implementación** — `PromptRegistry` y `ResourceRegistry` están declarados como puertos en `ports/` pero no tienen ninguna implementación ni uso en el código. Son código muerto.
3. **modelTimeout no aplicado** — `AgentConfig` define `modelTimeout` (30s por defecto) pero `AgentLoop` nunca lo usa. Si el modelo se cuelga, el loop espera indefinidamente.
4. **Dependencia externa de parent POM** — `ether-brain` requiere `../../ether-parent/ether-parent/pom.xml` para compilar. Si ese repositorio no está clonado localmente, el build falla. Esto debería resolverse con un BOM publicado en Maven Central o un parent local.

### Deuda técnica identificada

| Archivo/Módulo | Problema |
|---------------|---------|
| `AgentLoop.java` | `renderToolResult` usa `String.formatted` con formato frágil; `throws Exception` demasiado amplio; sin manejo de timeout |
| `PromptBuilder.java` | Instrucciones de sistema hardcodeadas en inglés; sin soporte de internacionalización; los bloques `TOOL:`/`FINAL:` son parseo frágil para `DemoModelClient` |
| `ApplicationBootstrap.java` | `DemoModelClient` es una inner class privada — difícil de testear y reemplazar; lógica de demo mezclada con wiring |
| `DefaultPolicyEngine.java` | Solo valida conteo de pasos y tamaño de historial — sin validación de contenido, seguridad de tools, ni rate limiting |

### Prácticas del lenguaje no seguidas

1. **Sin Javadoc** — Ninguna clase pública tiene Javadoc. El plugin `maven-javadoc-plugin` está configurado con `failOnError=false` y `doclint=none`, lo que oculta la ausencia total de documentación.
2. **`throws Exception` genérico** — `AgentLoop.run()` y `Tool.execute()` declaran `throws Exception` en lugar de excepciones tipadas del dominio.
3. **Sin `@Override` en métodos de interfaces funcionales** — algunas lambdas no lo requieren, pero en tests se usa consistentemente (bueno).
4. **Inner class privada como adaptador** — `DemoModelClient` debería ser una clase top-level en un módulo `infra-model-demo` o similar, no una inner class de bootstrap.

### Riesgos de seguridad

- **Bajo riesgo general** — proyecto en fase temprana, sin exposición a red.
- `EchoTool` ejecuta cualquier input del usuario sin sanitización (aunque solo devuelve el string, no ejecuta comandos).
- No se detectaron secretos expuestos (`.env`, API keys, etc.).
- Dependencias con versiones fijas (JUnit 5.12.2, ArchUnit 1.4.1) — positivo.

### Cobertura de tests y documentación

| Componente | Tests | Javadoc |
|-----------|-------|---------|
| `AgentLoop` | 2 tests ✅ | ❌ |
| `CompositeToolRegistry` | 4 tests ✅ | ❌ |
| `HexagonalArchitectureTest` | 1 regla ArchUnit ✅ | N/A |
| `PromptBuilder` | 0 tests ❌ | ❌ |
| `DefaultPolicyEngine` | 0 tests ❌ | ❌ |
| `DefaultToolExecutor` | 0 tests ❌ | ❌ |
| `InMemorySessionStore` | 0 tests ❌ | ❌ |
| `InMemoryToolRegistry` | 0 tests ❌ | ❌ |
| `AgentRuntime` | 0 tests ❌ | ❌ |
| `ApplicationBootstrap` | 0 tests ❌ | ❌ |
| `Main` | 0 tests ❌ | ❌ |
| Todas las interfaces `ports/` | 0 tests (no necesario) | ❌ |

**Total: 3 archivos de test (7 casos), cobertura estimada < 10%.**

---

## 3. Síntesis ejecutiva

### Resumen del proyecto

**EtherBrain** es un runtime de agente IA determinista, construido en **Java 21** con **arquitectura hexagonal** y **Maven** multi-módulo. Su objetivo es orquestar un loop de agente confiable: recibir input de usuario → construir prompt → consultar modelo → ejecutar tools → iterar hasta respuesta final. El proyecto está en fase **v0 temprana** (37 días, 15 commits, 2 contribuidores). La arquitectura está correctamente planteada con 8 módulos que separan puertos, núcleo, adaptadores y entrada/salida, pero la implementación aún es un esqueleto funcional con modelo simulado.

### Estado de salud

**🟡 Amarillo** — La arquitectura es sólida y bien diseñada, pero la implementación es mínima: cobertura de tests < 10%, sin adaptador real de LLM, sin Javadoc, y con dependencia externa de un parent POM no publicado. El proyecto tiene bases correctas pero necesita inversión urgente en tests y adaptadores reales para ser viable.

### Top 3 fortalezas

1. **Arquitectura hexagonal correctamente implementada** — puertos limpios (interfaces de 1-3 métodos), sin dependencias inversas. `ModelResponse` como sealed interface con `FinalAnswer` y `ToolRequest` es elegante. ArchUnit valida que core no depende de infraestructura.
2. **Uso idiomático de Java 21** — `record` para DTOs (`AgentConfig`, `ExecutionContext`, `ToolRequest`, `FinalAnswer`), sealed interfaces, pattern matching (`instanceof FinalAnswer finalAnswer`). Código conciso y moderno.
3. **Documentación SpecNative completa** — `agents/` contiene `PRODUCT.md`, `ARCHITECTURE.md`, `STACK.md`, `DECISIONS.md`, `SPEC.md`, `ROADMAP.md`. El qué, por qué y cómo del proyecto están bien definidos para onboarding de agentes y desarrolladores.

### Top 3 riesgos o deudas

1. **Cobertura de tests casi nula (3 archivos, 7 casos)** — `AgentLoop`, `PromptBuilder`, `DefaultPolicyEngine`, `DefaultToolExecutor`, `InMemorySessionStore` y `AgentRuntime` no tienen tests. Cualquier cambio rompe el runtime sin detección.
2. **Sin adaptador real de modelo LLM** — `DemoModelClient` hardcodeado en bootstrap con reglas `if/else`. El runtime no puede conectarse a OpenAI, Anthropic, ni ningún proveedor real. Es un bloqueante para cualquier uso real.
3. **API pública sin Javadoc** — todas las interfaces de `ports/` y clases de `core/` carecen de documentación. Esto frena la adopción, el onboarding y la generación de javadoc para Maven Central.

### Próximos pasos recomendados

1. **Escribir tests para el núcleo** — `AgentLoop`, `PromptBuilder`, `DefaultPolicyEngine`, `DefaultToolExecutor`, `AgentRuntime`. Prioridad: tests de integración del loop completo con un modelo mock. Impacto: 🔴 crítico para confiabilidad.
2. **Implementar un adaptador real de ModelClient** — crear `ether-brain-infra-model-openai` con cliente HTTP a OpenAI, usando el puerto `ModelClient` existente. Extraer `DemoModelClient` a su propio módulo `ether-brain-infra-model-demo`. Impacto: 🔴 bloqueante para uso real.
3. **Agregar Javadoc a la API pública** — documentar todas las interfaces de `ports/` y clases públicas de `core/`. Activar `failOnError=true` en `maven-javadoc-plugin`. Impacto: 🟡 medio para adopción.
4. **Wirear `modelTimeout` en `AgentLoop`** — usar `CompletableFuture` con timeout o `Future.get(timeout)`. Impacto: 🟡 medio para robustez.
5. **Agregar CI/CD con GitHub Actions** — build + tests en cada push/PR. Publicar snapshots a Maven Central. Impacto: 🟡 medio para profesionalización.
6. **Resolver dependencia de `ether-parent`** — publicar `ether-parent` como BOM en Maven Central o embeberlo como parent local. Impacto: 🟡 medio para portabilidad.

---

## 4. Archivos relevantes

| Archivo | Tipo | Relevancia |
|---------|------|------------|
| `ether-brain/pom.xml` | config | Parent POM multi-módulo — define los 8 módulos, Java 21, plugins de build y publicación a Maven Central |
| `ether-brain/ether-brain-ports/src/main/java/dev/rafex/etherbrain/ports/model/ModelResponse.java` | contract | Sealed interface raíz del contrato de respuesta del modelo — `FinalAnswer` y `ToolRequest` |
| `ether-brain/ether-brain-ports/src/main/java/dev/rafex/etherbrain/ports/model/ModelClient.java` | contract | Puerto de salida principal — genera respuestas desde un proveedor LLM. Solo tiene 1 método |
| `ether-brain/ether-brain-core/src/main/java/dev/rafex/etherbrain/core/runtime/AgentLoop.java` | core | Orquestador central del loop — construye prompt, consulta modelo, ejecuta tools, decide fin |
| `ether-brain/ether-brain-core/src/main/java/dev/rafex/etherbrain/core/runtime/AgentRuntime.java` | core | Fachada principal — inicia ejecución, carga/guarda sesión |
| `ether-brain/ether-brain-bootstrap/src/main/java/dev/rafex/etherbrain/bootstrap/ApplicationBootstrap.java` | entry | Wiring de dependencias con `DemoModelClient` hardcodeado — necesita refactor para extraer el demo |
| `ether-brain/ether-brain-transport-cli/src/main/java/dev/rafex/etherbrain/cli/Main.java` | entry | Punto de entrada CLI — `public static void main` |
| `ether-brain/ether-brain-architecture-tests/src/test/java/.../HexagonalArchitectureTest.java` | test | Única validación estructural — verifica que core no depende de infraestructura |
| `agents/ARCHITECTURE.md` | doc | Documento de arquitectura — define puertos, casos de uso, flujo principal y reglas de dependencia |
| `agents/PRODUCT.md` | doc | Visión de producto — problema, usuarios, objetivos, métricas y no-objetivos |
| `.gitignore` | config | Ignora `target/`, `.opencode/worktrees/`, `.idea/`, logs, etc. Tiene modificación pendiente |
