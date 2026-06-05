package dev.rafex.etherbrain.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * Verifica las restricciones de dependencias de la arquitectura hexagonal.
 *
 * <h2>Capas del sistema</h2>
 * <pre>
 *  ┌──────────────────────────────────────┐
 *  │  transport.*  (cli | http | mqtt)    │  ← entrada del mundo exterior
 *  └──────────────┬───────────────────────┘
 *                 │ depende de ↓
 *  ┌──────────────┴───────────────────────┐
 *  │  bootstrap                           │  ← montaje del runtime
 *  └──────────────┬───────────────────────┘
 *                 │ depende de ↓
 *  ┌──────────────┴───────────────────────┐
 *  │  core                                │  ← lógica de negocio pura
 *  └──────────────┬───────────────────────┘
 *                 │ depende de ↓
 *  ┌──────────────┴───────────────────────┐
 *  │  ports                               │  ← interfaces / contratos
 *  └──────────────────────────────────────┘
 *        ↑ implementan
 *  ┌─────────────────────────────────────┐
 *  │  infra.*  /  tools.*               │  ← adaptadores secundarios
 *  └─────────────────────────────────────┘
 * </pre>
 *
 * <h2>Reglas</h2>
 * <ol>
 *   <li>{@code core} no puede importar {@code infra}, {@code tools}, {@code bootstrap}
 *       ni ningún transporte — el core solo conoce los puertos.</li>
 *   <li>{@code ports} no puede importar nada interno del proyecto — es el
 *       contrato puro, no puede depender de ninguna implementación.</li>
 *   <li>{@code bootstrap} no puede importar transportes — el bootstrap construye
 *       el runtime pero no conoce cómo se expone (HTTP, CLI, MQTT…).</li>
 *   <li>Los transportes no pueden importarse entre sí — HTTP no debe depender
 *       de MQTT ni de CLI; cada transporte es independiente.</li>
 * </ol>
 */
@AnalyzeClasses(
        packages = "dev.rafex.etherbrain",
        importOptions = ImportOption.DoNotIncludeTests.class
)
class HexagonalArchitectureTest {

    // ── Regla 1: core aislado ─────────────────────────────────────────────────

    /**
     * El core solo depende de los puertos.
     * No puede conocer ninguna implementación concreta ni ningún transporte.
     */
    @ArchTest
    static final ArchRule core_does_not_depend_on_infrastructure =
            noClasses()
                    .that().resideInAPackage("..core..")
                    .should().dependOnClassesThat()
                    .resideInAnyPackage(
                            "..infra..",
                            "..tools.local..",
                            "..tools.remote..",
                            "..cli..",
                            "..http..",
                            "..mqtt..",
                            "..bootstrap..");

    // ── Regla 2: ports puros ──────────────────────────────────────────────────

    /**
     * Los puertos no pueden depender de ninguna clase interna del proyecto.
     * Son contratos puros: solo pueden usar JDK y librerías externas de terceros.
     */
    @ArchTest
    static final ArchRule ports_do_not_depend_on_internals =
            noClasses()
                    .that().resideInAPackage("..ports..")
                    .should().dependOnClassesThat()
                    .resideInAnyPackage(
                            "..core..",
                            "..infra..",
                            "..tools..",
                            "..bootstrap..",
                            "..cli..",
                            "..http..",
                            "..mqtt..");

    // ── Regla 3: bootstrap no conoce los transportes ─────────────────────────

    /**
     * Bootstrap ensambla el runtime pero no sabe cómo se expone.
     * HTTP, CLI y MQTT son responsabilidad de sus propios módulos de transporte.
     */
    @ArchTest
    static final ArchRule bootstrap_does_not_depend_on_transports =
            noClasses()
                    .that().resideInAPackage("..bootstrap..")
                    .should().dependOnClassesThat()
                    .resideInAnyPackage(
                            "..cli..",
                            "..http..",
                            "..mqtt..");

    // ── Regla 4: transportes independientes entre sí ──────────────────────────

    /**
     * HTTP no debe importar MQTT ni CLI, MQTT no debe importar HTTP ni CLI, etc.
     * Cada transporte es un adaptador de entrada independiente.
     */
    @ArchTest
    static final ArchRule http_transport_does_not_depend_on_other_transports =
            noClasses()
                    .that().resideInAPackage("..http..")
                    .should().dependOnClassesThat()
                    .resideInAnyPackage("..cli..", "..mqtt..");

    @ArchTest
    static final ArchRule mqtt_transport_does_not_depend_on_other_transports =
            noClasses()
                    .that().resideInAPackage("..mqtt..")
                    .should().dependOnClassesThat()
                    .resideInAnyPackage("..cli..", "..http..");

    @ArchTest
    static final ArchRule cli_transport_does_not_depend_on_other_transports =
            noClasses()
                    .that().resideInAPackage("..cli..")
                    .should().dependOnClassesThat()
                    .resideInAnyPackage("..http..", "..mqtt..");
}
