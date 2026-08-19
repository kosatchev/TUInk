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
    private static final String KEY_DECODER = "dev.tuink.terminal.jline.KeyDecoder";

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

    static final ArchRule TERMINAL_FOUNDATION_IS_SELF_CONTAINED = classes()
        .that().resideInAPackage("dev.tuink.terminal..")
        .and().resideOutsideOfPackage("dev.tuink.terminal.jline..")
        .should().onlyDependOnClassesThat().resideInAnyPackage(
            "java..",
            "dev.tuink.unicode..",
            "dev.tuink.terminal.."
        );

    static final ArchRule TERMINAL_FOUNDATION_DOES_NOT_DEPEND_ON_JLINE_BACKEND = noClasses()
        .that().resideInAPackage("dev.tuink.terminal..")
        .and().resideOutsideOfPackage("dev.tuink.terminal.jline..")
        .should().dependOnClassesThat().resideInAPackage("dev.tuink.terminal.jline..");

    static final ArchRule CORE_IS_TERMINAL_INDEPENDENT = classes()
        .that().resideInAPackage("dev.tuink.core..")
        .should().onlyDependOnClassesThat().resideInAnyPackage(
            "java..",
            "org.eclipse.jgit..",
            "dev.tuink.core..",
            "dev.tuink.unicode.."
        );

    static final ArchRule OUTER_LAYERS_DO_NOT_MUTATE_CURSOR_STATE_DIRECTLY = noClasses()
        .that().resideOutsideOfPackage("dev.tuink.core..")
        .should().callMethod(
            "dev.tuink.core.EditorState",
            "setCursor",
            "dev.tuink.core.Position"
        )
        .orShould().callMethod(
            "dev.tuink.core.EditorState",
            "setSelectionAnchor",
            "dev.tuink.core.Position"
        )
        .orShould().callMethod("dev.tuink.core.EditorState", "clearSelection");

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

    static final ArchRule CONTEXTUAL_HIGHLIGHTER_DOES_NOT_EXPOSE_MUTABLE_MODEL = noClasses()
        .that().haveFullyQualifiedName("dev.tuink.render.ContextualLineHighlighter")
        .should().dependOnClassesThat().resideInAPackage("dev.tuink.core..");

    static final ArchRule HIGHLIGHTER_IMPLEMENTATIONS_STAY_CORE_INDEPENDENT = noClasses()
        .that().resideInAPackage("dev.tuink.highlight..")
        .should().dependOnClassesThat().resideInAPackage("dev.tuink.core..");

    static final ArchRule TUI_DOES_NOT_DEPEND_ON_APPLICATION_ENTRYPOINT = noClasses()
        .that().resideInAPackage("dev.tuink.tui..")
        .should().dependOnClassesThat().resideInAPackage("dev.tuink.app..");

    static final ArchRule APP_USES_ONLY_THE_TUI_FACADE = noClasses()
        .that().resideInAPackage("dev.tuink.app..")
        .should().dependOnClassesThat().resideInAnyPackage(
            "dev.tuink.core..",
            "dev.tuink.render..",
            "dev.tuink.highlight..",
            "dev.tuink.terminal..",
            "dev.tuink.tui.flow..",
            "dev.tuink.tui.input..",
            "dev.tuink.tui.render..",
            "dev.tuink.tui.style..",
            "dev.tuink.tui.terminal..",
            "dev.tuink.tui.ui.."
        );

    static final ArchRule JLINE_BACKEND_IS_EDITOR_INDEPENDENT = classes()
        .that().resideInAPackage("dev.tuink.terminal.jline..")
        .should().onlyDependOnClassesThat().resideInAnyPackage(
            "java..",
            "org.jline..",
            "dev.tuink.terminal.."
        );

    static final ArchRule PHYSICAL_INPUT_MODEL_IS_SELF_CONTAINED = classes()
        .that().resideInAPackage("dev.tuink.terminal.input..")
        .should().onlyDependOnClassesThat().resideInAnyPackage(
            "java..",
            "dev.tuink.terminal.input.."
        );

    static final ArchRule KEY_DECODER_IS_A_SELF_CONTAINED_JLINE_ADAPTER = classes()
        .that().haveFullyQualifiedName(KEY_DECODER)
        .should().onlyDependOnClassesThat().resideInAnyPackage(
            "java..",
            "org.jline..",
            "dev.tuink.terminal.input..",
            "dev.tuink.terminal.jline.."
        );

    static final ArchRule KEY_DECODER_DOES_NOT_DEPEND_ON_LEGACY_INPUT = noClasses()
        .that().haveFullyQualifiedName(KEY_DECODER)
        .should().dependOnClassesThat().haveNameMatching(
            "dev\\.tuink\\.tui\\.input\\.(?:KeyEvent|LegacyKeyEventAdapter)(\\$.*)?"
        );

    static final ArchRule PHYSICAL_INPUT_EVENTS_DO_NOT_DEPEND_ON_THE_LEGACY_MODEL = noClasses()
        .that().haveNameMatching(
            "dev\\.tuink\\.terminal\\.input\\.(?:TuiInputEvent|KeyStroke|TextInput|PasteEvent|PointerEvent|ResizeEvent)(\\$.*)?"
        )
        .should().dependOnClassesThat().haveNameMatching(
            "dev\\.tuink\\.tui\\.input\\.(?:KeyEvent|LegacyKeyEventAdapter)(\\$.*)?"
        );

    static final ArchRule COMMAND_CONTRACT_IS_SELF_CONTAINED = classes()
        .that().resideInAPackage("dev.tuink.editor.command..")
        .should().onlyDependOnClassesThat().resideInAnyPackage(
            "java..",
            "dev.tuink.editor.command.."
        );

    static final ArchRule DOCUMENT_CONTRACT_IS_SELF_CONTAINED = classes()
        .that().resideInAPackage("dev.tuink.editor.document..")
        .should().onlyDependOnClassesThat().resideInAnyPackage(
            "java..",
            "dev.tuink.editor.document.."
        );

    static final ArchRule EDITOR_SESSION_STAYS_FRONTEND_INDEPENDENT = classes()
        .that().resideInAPackage("dev.tuink.editor.session..")
        .should().onlyDependOnClassesThat().resideInAnyPackage(
            "java..",
            "dev.tuink.core..",
            "dev.tuink.editor.command..",
            "dev.tuink.editor.document..",
            "dev.tuink.editor.session.."
        );

    static final ArchRule KEYMAP_LAYER_STAYS_EDITOR_INDEPENDENT = classes()
        .that().resideInAPackage("dev.tuink.tui.keymap..")
        .should().onlyDependOnClassesThat().resideInAnyPackage(
            "java..",
            "dev.tuink.editor.command..",
            "dev.tuink.tui.command..",
            "dev.tuink.terminal.input..",
            "dev.tuink.tui.keymap.."
        );

    static final ArchRule GENERIC_KEYMAP_DOES_NOT_IMPORT_PHYSICAL_OR_LEGACY_INPUT = classes()
        .that().haveFullyQualifiedName("dev.tuink.tui.keymap.Keymap")
        .should().onlyDependOnClassesThat().resideInAnyPackage(
            "java..",
            "dev.tuink.editor.command..",
            "dev.tuink.tui.keymap.."
        );

    static final ArchRule TUINK_KEYMAP_DOES_NOT_IMPORT_DECODER_OR_LEGACY_EVENTS = noClasses()
        .that().haveFullyQualifiedName("dev.tuink.tui.keymap.TuinkKeymap")
        .should().dependOnClassesThat().haveNameMatching(
            "dev\\.tuink\\.(?:terminal\\.jline\\.KeyDecoder|"
                + "tui\\.input\\.(?:KeyEvent|LegacyKeyEventAdapter))(?:\\$.*)?"
        );

    static final ArchRule EVENT_LOOP_IS_EDITOR_INDEPENDENT = classes()
        .that().resideInAPackage("dev.tuink.terminal.runtime..")
        .should().onlyDependOnClassesThat().resideInAnyPackage(
            "java..",
            "dev.tuink.terminal.runtime.."
        );

    static final ArchRule WIDGET_LAYER_STAYS_EDITOR_INDEPENDENT = classes()
        .that().resideInAPackage("dev.tuink.terminal.widget..")
        .should().onlyDependOnClassesThat().resideInAnyPackage(
            "java..",
            "dev.tuink.terminal.."
        );

    static final ArchRule WIDGET_LAYER_STAYS_FILESYSTEM_FREE = noClasses()
        .that().resideInAPackage("dev.tuink.terminal.widget..")
        .should().dependOnClassesThat().resideInAnyPackage(
            "java.io..",
            "java.nio.file.."
        );

    static final ArchRule TUI_GEOMETRY_IS_SELF_CONTAINED = classes()
        .that().resideInAPackage("dev.tuink.terminal.geometry..")
        .should().onlyDependOnClassesThat().resideInAnyPackage(
            "java..",
            "dev.tuink.terminal.geometry.."
        );

    static final ArchRule CELL_STYLE_IS_SELF_CONTAINED = classes()
        .that().haveNameMatching("dev\\.tuink\\.terminal\\.style\\.Cell(?:Color|Style)(?:\\$.*)?")
        .should().onlyDependOnClassesThat().haveNameMatching(
            "(?:\\[+[ZBCSIJFD]|\\[+Ldev\\.tuink\\.terminal\\.style\\.Cell(?:Color|Style);|"
                + "java\\..*|dev\\.tuink\\.terminal\\.style\\.Cell(?:Color|Style)(?:\\$.*)?)"
        );

    static final ArchRule CELL_MODEL_DOES_NOT_DEPEND_ON_EDITOR_RENDER = noClasses()
        .that().haveNameMatching(
            "dev\\.tuink\\.terminal\\.cell\\.(?:Cell|CellBuffer|CellText|Canvas)(?:\\$.*)?"
        )
        .should().dependOnClassesThat().resideInAnyPackage(
            "dev.tuink.core..",
            "dev.tuink.render..",
            "dev.tuink.tui.."
        );

    static final ArchRule SCREEN_MODEL_STAYS_FOUNDATION_ONLY = classes()
        .that().resideInAPackage("dev.tuink.terminal.screen..")
        .should().onlyDependOnClassesThat().haveNameMatching(
            "(?:\\[+[ZBCSIJFD]|java\\..*|dev\\.tuink\\.terminal\\.geometry\\..*|"
                + "dev\\.tuink\\.terminal\\.screen\\..*|dev\\.tuink\\.terminal\\.cell\\.CellBuffer(?:\\$.*)?)"
        );

    static final ArchRule SCREEN_PRESENTATION_STAYS_EDITOR_INDEPENDENT = classes()
        .that().resideInAPackage("dev.tuink.terminal.jline.presentation..")
        .should().onlyDependOnClassesThat().resideInAnyPackage(
            "java..",
            "dev.tuink.terminal..",
            "dev.tuink.terminal.jline.presentation.."
        );

    static final ArchRule TERMINAL_EDITOR_APP_DOES_NOT_BYPASS_SCREEN_PRESENTER = noClasses()
        .that().haveFullyQualifiedName("dev.tuink.tui.TerminalEditorApp")
        .should().dependOnClassesThat().haveNameMatching(
            "dev\\.tuink\\.(?:terminal\\.jline\\.presentation\\.AnsiDiff|"
                + "tui\\.ui\\.TerminalCursorController)"
        );

    static final ArchRule JLINE_STAYS_IN_BACKEND = noClasses()
        .that().resideOutsideOfPackage("dev.tuink.terminal.jline..")
        .should().dependOnClassesThat().resideInAPackage("org.jline..");

    static final ArchRule TOP_LEVEL_SLICES_ARE_FREE_OF_CYCLES = slices()
        .matching("dev.tuink.(*)..")
        .should().beFreeOfCycles();

    static final ArchRule TUI_SUBPACKAGE_SLICES_ARE_FREE_OF_CYCLES = slices()
        .matching("dev.tuink.tui.(*)..")
        .should().beFreeOfCycles();

    static final ArchRule TERMINAL_SUBPACKAGE_SLICES_ARE_FREE_OF_CYCLES = slices()
        .matching("dev.tuink.terminal.(*)..")
        .should().beFreeOfCycles();

    @Test
    void moduleDependenciesPointInward() {
        UNICODE_TEXT_IS_PURE.check(PRODUCTION_CLASSES);
        TERMINAL_FOUNDATION_IS_SELF_CONTAINED.check(PRODUCTION_CLASSES);
        TERMINAL_FOUNDATION_DOES_NOT_DEPEND_ON_JLINE_BACKEND.check(PRODUCTION_CLASSES);
        CORE_DOES_NOT_DEPEND_ON_OUTER_LAYERS.check(PRODUCTION_CLASSES);
        CORE_IS_TERMINAL_INDEPENDENT.check(PRODUCTION_CLASSES);
        OUTER_LAYERS_DO_NOT_MUTATE_CURSOR_STATE_DIRECTLY.check(PRODUCTION_CLASSES);
        RENDER_DOES_NOT_DEPEND_ON_FRONTENDS.check(PRODUCTION_CLASSES);
        HIGHLIGHTERS_DO_NOT_DEPEND_ON_FRONTENDS.check(PRODUCTION_CLASSES);
        CONTEXTUAL_HIGHLIGHTER_DOES_NOT_EXPOSE_MUTABLE_MODEL.check(PRODUCTION_CLASSES);
        HIGHLIGHTER_IMPLEMENTATIONS_STAY_CORE_INDEPENDENT.check(PRODUCTION_CLASSES);
        TUI_DOES_NOT_DEPEND_ON_APPLICATION_ENTRYPOINT.check(PRODUCTION_CLASSES);
        APP_USES_ONLY_THE_TUI_FACADE.check(PRODUCTION_CLASSES);
        TOP_LEVEL_SLICES_ARE_FREE_OF_CYCLES.check(PRODUCTION_CLASSES);
        TUI_SUBPACKAGE_SLICES_ARE_FREE_OF_CYCLES.check(PRODUCTION_CLASSES);
        TERMINAL_SUBPACKAGE_SLICES_ARE_FREE_OF_CYCLES.check(PRODUCTION_CLASSES);
    }

    @Test
    void terminalFoundationStaysEditorIndependent() {
        JLINE_BACKEND_IS_EDITOR_INDEPENDENT.check(PRODUCTION_CLASSES);
        EVENT_LOOP_IS_EDITOR_INDEPENDENT.check(PRODUCTION_CLASSES);
        WIDGET_LAYER_STAYS_EDITOR_INDEPENDENT.check(PRODUCTION_CLASSES);
        WIDGET_LAYER_STAYS_FILESYSTEM_FREE.check(PRODUCTION_CLASSES);
        TUI_GEOMETRY_IS_SELF_CONTAINED.check(PRODUCTION_CLASSES);
        CELL_STYLE_IS_SELF_CONTAINED.check(PRODUCTION_CLASSES);
        CELL_MODEL_DOES_NOT_DEPEND_ON_EDITOR_RENDER.check(PRODUCTION_CLASSES);
        SCREEN_MODEL_STAYS_FOUNDATION_ONLY.check(PRODUCTION_CLASSES);
        SCREEN_PRESENTATION_STAYS_EDITOR_INDEPENDENT.check(PRODUCTION_CLASSES);
        TERMINAL_EDITOR_APP_DOES_NOT_BYPASS_SCREEN_PRESENTER.check(PRODUCTION_CLASSES);
        JLINE_STAYS_IN_BACKEND.check(PRODUCTION_CLASSES);
    }

    @Test
    void inputCommandsAndKeymapStayReusable() {
        PHYSICAL_INPUT_MODEL_IS_SELF_CONTAINED.check(PRODUCTION_CLASSES);
        KEY_DECODER_IS_A_SELF_CONTAINED_JLINE_ADAPTER.check(PRODUCTION_CLASSES);
        KEY_DECODER_DOES_NOT_DEPEND_ON_LEGACY_INPUT.check(PRODUCTION_CLASSES);
        PHYSICAL_INPUT_EVENTS_DO_NOT_DEPEND_ON_THE_LEGACY_MODEL.check(PRODUCTION_CLASSES);
        COMMAND_CONTRACT_IS_SELF_CONTAINED.check(PRODUCTION_CLASSES);
        DOCUMENT_CONTRACT_IS_SELF_CONTAINED.check(PRODUCTION_CLASSES);
        EDITOR_SESSION_STAYS_FRONTEND_INDEPENDENT.check(PRODUCTION_CLASSES);
        KEYMAP_LAYER_STAYS_EDITOR_INDEPENDENT.check(PRODUCTION_CLASSES);
        GENERIC_KEYMAP_DOES_NOT_IMPORT_PHYSICAL_OR_LEGACY_INPUT.check(PRODUCTION_CLASSES);
        TUINK_KEYMAP_DOES_NOT_IMPORT_DECODER_OR_LEGACY_EVENTS.check(PRODUCTION_CLASSES);
    }
}
