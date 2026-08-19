package dev.tuink.app;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

class RenderCacheBoundaryArchitectureTest {
    private static final JavaClasses PRODUCTION_CLASSES = new ClassFileImporter()
        .withImportOption(new ImportOption.DoNotIncludeTests())
        .importPackages("dev.tuink");

    @Test
    void baseStyleIdentityRemainsAProducerRasterizerProtocol() {
        noClasses()
            .that().resideOutsideOfPackages("dev.tuink.render..", "dev.tuink.tui.render..")
            .should().dependOnClassesThat()
            .haveFullyQualifiedName("dev.tuink.render.BaseStyleIdentity")
            .check(PRODUCTION_CLASSES);
    }
}
