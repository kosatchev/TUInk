package dev.tuink.app;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;

class ArchitectureTest {
    private static final JavaClasses PRODUCTION_CLASSES = new ClassFileImporter()
        .withImportOption(new ImportOption.DoNotIncludeTests())
        .importPackages("dev.tuink");

    static final ArchRule CORE_DOES_NOT_DEPEND_ON_OUTER_LAYERS = noClasses()
        .that().resideInAPackage("dev.tuink.core..")
        .should().dependOnClassesThat().resideInAnyPackage(
            "dev.tuink.render..",
            "dev.tuink.highlight..",
            "dev.tuink.tui..",
            "dev.tuink.app.."
        );

    static final ArchRule RENDER_DOES_NOT_DEPEND_ON_FRONTENDS = noClasses()
        .that().resideInAPackage("dev.tuink.render..")
        .should().dependOnClassesThat().resideInAnyPackage(
            "dev.tuink.highlight..",
            "dev.tuink.tui..",
            "dev.tuink.app.."
        );

    static final ArchRule HIGHLIGHTERS_DO_NOT_DEPEND_ON_FRONTENDS = noClasses()
        .that().resideInAPackage("dev.tuink.highlight..")
        .should().dependOnClassesThat().resideInAnyPackage(
            "dev.tuink.tui..",
            "dev.tuink.app.."
        );

    static final ArchRule TUI_DOES_NOT_DEPEND_ON_APPLICATION_ENTRYPOINT = noClasses()
        .that().resideInAPackage("dev.tuink.tui..")
        .should().dependOnClassesThat().resideInAPackage("dev.tuink.app..");

    static final ArchRule APP_USES_ONLY_THE_TUI_FACADE = noClasses()
        .that().resideInAPackage("dev.tuink.app..")
        .should().dependOnClassesThat().resideInAnyPackage(
            "dev.tuink.core..",
            "dev.tuink.render..",
            "dev.tuink.highlight..",
            "dev.tuink.tui.flow..",
            "dev.tuink.tui.input..",
            "dev.tuink.tui.render..",
            "dev.tuink.tui.runtime..",
            "dev.tuink.tui.style..",
            "dev.tuink.tui.terminal..",
            "dev.tuink.tui.ui.."
        );

    static final ArchRule TERMINAL_RUNTIME_IS_EDITOR_INDEPENDENT = classes()
        .that().resideInAPackage("dev.tuink.tui.terminal..")
        .should().onlyDependOnClassesThat().resideInAnyPackage(
            "java..",
            "org.jline..",
            "dev.tuink.tui.terminal.."
        );

    static final ArchRule INPUT_DECODING_IS_EDITOR_INDEPENDENT = classes()
        .that().resideInAPackage("dev.tuink.tui.input..")
        .should().onlyDependOnClassesThat().resideInAnyPackage(
            "java..",
            "org.jline..",
            "dev.tuink.tui.input.."
        );

    static final ArchRule EVENT_LOOP_IS_EDITOR_INDEPENDENT = classes()
        .that().resideInAPackage("dev.tuink.tui.runtime..")
        .should().onlyDependOnClassesThat().resideInAnyPackage(
            "java..",
            "dev.tuink.tui.runtime.."
        );

    static final ArchRule JLINE_STAYS_IN_TUI_EXCEPT_FOR_THE_KNOWN_METRICS_DEBT = noClasses()
        .that().resideOutsideOfPackage("dev.tuink.tui..")
        .and().doNotHaveFullyQualifiedName("dev.tuink.core.DisplayText")
        .should().dependOnClassesThat().resideInAPackage("org.jline..");

    static final ArchRule TOP_LEVEL_SLICES_ARE_FREE_OF_CYCLES = slices()
        .matching("dev.tuink.(*)..")
        .should().beFreeOfCycles();

    @Test
    void moduleDependenciesPointInward() {
        CORE_DOES_NOT_DEPEND_ON_OUTER_LAYERS.check(PRODUCTION_CLASSES);
        RENDER_DOES_NOT_DEPEND_ON_FRONTENDS.check(PRODUCTION_CLASSES);
        HIGHLIGHTERS_DO_NOT_DEPEND_ON_FRONTENDS.check(PRODUCTION_CLASSES);
        TUI_DOES_NOT_DEPEND_ON_APPLICATION_ENTRYPOINT.check(PRODUCTION_CLASSES);
        APP_USES_ONLY_THE_TUI_FACADE.check(PRODUCTION_CLASSES);
        TOP_LEVEL_SLICES_ARE_FREE_OF_CYCLES.check(PRODUCTION_CLASSES);
    }

    @Test
    void terminalFoundationStaysEditorIndependent() {
        TERMINAL_RUNTIME_IS_EDITOR_INDEPENDENT.check(PRODUCTION_CLASSES);
        INPUT_DECODING_IS_EDITOR_INDEPENDENT.check(PRODUCTION_CLASSES);
        EVENT_LOOP_IS_EDITOR_INDEPENDENT.check(PRODUCTION_CLASSES);
        JLINE_STAYS_IN_TUI_EXCEPT_FOR_THE_KNOWN_METRICS_DEBT.check(PRODUCTION_CLASSES);
    }
}
