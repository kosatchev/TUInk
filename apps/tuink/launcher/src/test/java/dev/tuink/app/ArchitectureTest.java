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
    private static final String KEY_DECODER = "dev.tuink.tui.input.KeyDecoder";

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

    static final ArchRule UNICODE_TEXT_IS_PURE = classes()
        .that().resideInAPackage("dev.tuink.unicode..")
        .should().onlyDependOnClassesThat().resideInAnyPackage(
            "java..",
            "dev.tuink.unicode.."
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

    static final ArchRule PHYSICAL_INPUT_MODEL_IS_SELF_CONTAINED = classes()
        .that().resideInAPackage("dev.tuink.tui.input..")
        .and().doNotHaveFullyQualifiedName(KEY_DECODER)
        .should().onlyDependOnClassesThat().resideInAnyPackage(
            "java..",
            "dev.tuink.tui.input.."
        );

    static final ArchRule KEY_DECODER_IS_A_SELF_CONTAINED_JLINE_ADAPTER = classes()
        .that().haveFullyQualifiedName(KEY_DECODER)
        .should().onlyDependOnClassesThat().resideInAnyPackage(
            "java..",
            "org.jline..",
            "dev.tuink.tui.input.."
        );

    static final ArchRule KEY_DECODER_DOES_NOT_DEPEND_ON_LEGACY_INPUT = noClasses()
        .that().haveFullyQualifiedName(KEY_DECODER)
        .should().dependOnClassesThat().haveNameMatching(
            "dev\\.tuink\\.tui\\.input\\.(?:KeyEvent|LegacyKeyEventAdapter)(\\$.*)?"
        );

    static final ArchRule PHYSICAL_INPUT_EVENTS_DO_NOT_DEPEND_ON_THE_LEGACY_MODEL = noClasses()
        .that().haveNameMatching(
            "dev\\.tuink\\.tui\\.input\\.(?:TuiInputEvent|KeyStroke|TextInput|PasteEvent|PointerEvent|ResizeEvent)(\\$.*)?"
        )
        .should().dependOnClassesThat().haveNameMatching(
            "dev\\.tuink\\.tui\\.input\\.(?:KeyEvent|LegacyKeyEventAdapter)(\\$.*)?"
        );

    static final ArchRule COMMAND_MODEL_IS_SELF_CONTAINED = classes()
        .that().resideInAPackage("dev.tuink.tui.command..")
        .should().onlyDependOnClassesThat().resideInAnyPackage(
            "java..",
            "dev.tuink.tui.command.."
        );

    static final ArchRule KEYMAP_LAYER_STAYS_EDITOR_INDEPENDENT = classes()
        .that().resideInAPackage("dev.tuink.tui.keymap..")
        .should().onlyDependOnClassesThat().resideInAnyPackage(
            "java..",
            "dev.tuink.tui.command..",
            "dev.tuink.tui.input..",
            "dev.tuink.tui.keymap.."
        );

    static final ArchRule GENERIC_KEYMAP_DOES_NOT_IMPORT_PHYSICAL_OR_LEGACY_INPUT = classes()
        .that().haveFullyQualifiedName("dev.tuink.tui.keymap.Keymap")
        .should().onlyDependOnClassesThat().resideInAnyPackage(
            "java..",
            "dev.tuink.tui.command..",
            "dev.tuink.tui.keymap.."
        );

    static final ArchRule TUINK_KEYMAP_DOES_NOT_IMPORT_DECODER_OR_LEGACY_EVENTS = noClasses()
        .that().haveFullyQualifiedName("dev.tuink.tui.keymap.TuinkKeymap")
        .should().dependOnClassesThat().haveNameMatching(
            "dev\\.tuink\\.tui\\.input\\.(?:KeyDecoder|KeyEvent|LegacyKeyEventAdapter)(?:\\$.*)?"
        );

    static final ArchRule EVENT_LOOP_IS_EDITOR_INDEPENDENT = classes()
        .that().resideInAPackage("dev.tuink.tui.runtime..")
        .should().onlyDependOnClassesThat().resideInAnyPackage(
            "java..",
            "dev.tuink.tui.runtime.."
        );

    static final ArchRule TUI_GEOMETRY_IS_SELF_CONTAINED = classes()
        .that().resideInAPackage("dev.tuink.tui.geometry..")
        .should().onlyDependOnClassesThat().resideInAnyPackage(
            "java..",
            "dev.tuink.tui.geometry.."
        );

    static final ArchRule SCREEN_MODEL_STAYS_FOUNDATION_ONLY = classes()
        .that().resideInAPackage("dev.tuink.tui.screen..")
        .should().onlyDependOnClassesThat().haveNameMatching(
            "(?:\\[+[ZBCSIJFD]|java\\..*|dev\\.tuink\\.tui\\.geometry\\..*|"
                + "dev\\.tuink\\.tui\\.screen\\..*|dev\\.tuink\\.tui\\.render\\.CellBuffer(?:\\$.*)?)"
        );

    static final ArchRule SCREEN_PRESENTATION_STAYS_EDITOR_INDEPENDENT = classes()
        .that().resideInAPackage("dev.tuink.tui.presentation..")
        .should().onlyDependOnClassesThat().haveNameMatching(
            "(?:\\[+[ZBCSIJFD]|java\\..*|dev\\.tuink\\.render\\.(?:Color|Style)(?:\\$.*)?|"
                + "dev\\.tuink\\.tui\\.render\\.(?:Cell|CellBuffer)(?:\\$.*)?|"
                + "dev\\.tuink\\.tui\\.(?:geometry|screen|presentation)\\..*)"
        );

    static final ArchRule TERMINAL_EDITOR_APP_DOES_NOT_BYPASS_SCREEN_PRESENTER = noClasses()
        .that().haveFullyQualifiedName("dev.tuink.tui.TerminalEditorApp")
        .should().dependOnClassesThat().haveNameMatching(
            "dev\\.tuink\\.tui\\.(?:presentation\\.AnsiDiff|ui\\.TerminalCursorController)"
        );

    static final ArchRule JLINE_STAYS_IN_TUI = noClasses()
        .that().resideOutsideOfPackage("dev.tuink.tui..")
        .should().dependOnClassesThat().resideInAPackage("org.jline..");

    static final ArchRule TOP_LEVEL_SLICES_ARE_FREE_OF_CYCLES = slices()
        .matching("dev.tuink.(*)..")
        .should().beFreeOfCycles();

    @Test
    void moduleDependenciesPointInward() {
        UNICODE_TEXT_IS_PURE.check(PRODUCTION_CLASSES);
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
        EVENT_LOOP_IS_EDITOR_INDEPENDENT.check(PRODUCTION_CLASSES);
        TUI_GEOMETRY_IS_SELF_CONTAINED.check(PRODUCTION_CLASSES);
        SCREEN_MODEL_STAYS_FOUNDATION_ONLY.check(PRODUCTION_CLASSES);
        SCREEN_PRESENTATION_STAYS_EDITOR_INDEPENDENT.check(PRODUCTION_CLASSES);
        TERMINAL_EDITOR_APP_DOES_NOT_BYPASS_SCREEN_PRESENTER.check(PRODUCTION_CLASSES);
        JLINE_STAYS_IN_TUI.check(PRODUCTION_CLASSES);
    }

    @Test
    void inputCommandsAndKeymapStayReusable() {
        PHYSICAL_INPUT_MODEL_IS_SELF_CONTAINED.check(PRODUCTION_CLASSES);
        KEY_DECODER_IS_A_SELF_CONTAINED_JLINE_ADAPTER.check(PRODUCTION_CLASSES);
        KEY_DECODER_DOES_NOT_DEPEND_ON_LEGACY_INPUT.check(PRODUCTION_CLASSES);
        PHYSICAL_INPUT_EVENTS_DO_NOT_DEPEND_ON_THE_LEGACY_MODEL.check(PRODUCTION_CLASSES);
        COMMAND_MODEL_IS_SELF_CONTAINED.check(PRODUCTION_CLASSES);
        KEYMAP_LAYER_STAYS_EDITOR_INDEPENDENT.check(PRODUCTION_CLASSES);
        GENERIC_KEYMAP_DOES_NOT_IMPORT_PHYSICAL_OR_LEGACY_INPUT.check(PRODUCTION_CLASSES);
        TUINK_KEYMAP_DOES_NOT_IMPORT_DECODER_OR_LEGACY_EVENTS.check(PRODUCTION_CLASSES);
    }
}
