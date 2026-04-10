package dev.rafex.etherbrain.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

@AnalyzeClasses(
        packages = "dev.rafex.etherbrain",
        importOptions = ImportOption.DoNotIncludeTests.class
)
class HexagonalArchitectureTest {

    @ArchTest
    static final ArchRule core_does_not_depend_on_infrastructure =
            noClasses()
                    .that().resideInAPackage("..core..")
                    .should().dependOnClassesThat()
                    .resideInAnyPackage("..infra..", "..tools.local..", "..cli..", "..bootstrap..");
}
