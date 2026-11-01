package dev.tuink.app;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.library.dependencies.SliceAssignment;
import com.tngtech.archunit.library.dependencies.SliceIdentifier;
import org.junit.jupiter.api.Test;

class ArchitectureTest {
    private static final String KEY_DECODER = "dev.tuiframe.jline.KeyDecoder";

    private static final JavaClasses PRODUCTION_CLASSES = new ClassFileImporter()
        .withImportOption(new ImportOption.DoNotIncludeTests())
        .importPackages("dev.tuink", "dev.tuiframe");

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
        .that().resideInAPackage("dev.tuiframe..")
        .and().resideOutsideOfPackage("dev.tuiframe.jline..")
        .should().onlyDependOnClassesThat().resideInAnyPackage(
            "java..",
            "dev.tuiframe.."
        );

    static final ArchRule TERMINAL_FOUNDATION_DOES_NOT_DEPEND_ON_JLINE_BACKEND = noClasses()
        .that().resideInAPackage("dev.tuiframe..")
        .and().resideOutsideOfPackage("dev.tuiframe.jline..")
        .should().dependOnClassesThat().resideInAPackage("dev.tuiframe.jline..");

    static final ArchRule CORE_IS_TERMINAL_INDEPENDENT = classes()
        .that().resideInAPackage("dev.tuink.core..")
        .should().onlyDependOnClassesThat().resideInAnyPackage(
            "java..",
            "dev.tuink.core..",
            "dev.tuink.editor.document..",
            "dev.tuink.editor.command..",
            "dev.tuink.editor.format..",
            "dev.tuink.editor.search..",
            "dev.tuink.editor.backend..",
            "dev.tuink.editor.recovery..",
            "dev.tuink.editor.diff..",
            "dev.tuink.editor.storage..",
            "dev.tuink.editor.language..",
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

    static final ArchRule DIFF_PROVIDER_STAYS_BEHIND_ITS_CONTRACT = classes()
        .that().resideInAPackage("dev.tuink.diff.jgit..")
        .should().onlyDependOnClassesThat().resideInAnyPackage(
            "java..", "dev.tuink.diff.jgit..", "dev.tuink.editor.diff..",
            "dev.tuink.editor.document..", "org.eclipse.jgit.."
        );

    static final ArchRule JVM_STORAGE_DEPENDS_ONLY_ON_SHARED_SERVICES = classes()
        .that().resideInAPackage("dev.tuink.storage.jvm..")
        .should().onlyDependOnClassesThat(
            JavaClass.Predicates.resideInAnyPackage(
                "java..", "dev.tuink.storage.jvm..", "dev.tuink.editor.storage..", "dev.tuink.editor.document..")
                .or(com.tngtech.archunit.core.domain.properties.HasName.Predicates.nameMatching(
                    "dev\\.tuink\\.core\\.DocumentCodec(?:\\$.*)?"))
        );

    static final ArchRule SHARED_ENGINE_DOES_NOT_ACCESS_FILESYSTEM = noClasses()
        .that().resideInAnyPackage("dev.tuink.core..", "dev.tuink.editor..")
        .should().dependOnClassesThat().resideInAnyPackage(
            "java.nio.file..", "java.nio.channels..", "java.lang.foreign..",
            "dev.tuink.storage.jvm.."
        )
        .orShould().dependOnClassesThat().haveFullyQualifiedName("java.io.File");

    static final ArchRule STORAGE_CONTRACT_STAYS_PORTABLE = classes()
        .that().resideInAPackage("dev.tuink.editor.storage..")
        .should().onlyDependOnClassesThat().resideInAnyPackage(
            "java..", "dev.tuink.editor.storage..", "dev.tuink.editor.document.."
        );

    static final ArchRule TUI_DOES_NOT_DEPEND_ON_APPLICATION_ENTRYPOINT = noClasses()
        .that().resideInAPackage("dev.tuink.tui..")
        .should().dependOnClassesThat().resideInAPackage("dev.tuink.app..");

    static final ArchRule FILE_TREE_MODEL_STAYS_TERMINAL_INDEPENDENT = classes()
        .that().resideInAPackage("dev.tuink.tui.file..")
        .should().onlyDependOnClassesThat().resideInAnyPackage(
            "java..",
            "dev.tuink.tui.file.."
        );

    static final ArchRule APP_USES_ONLY_THE_TUI_FACADE = noClasses()
        .that().resideInAPackage("dev.tuink.app..")
        .should().dependOnClassesThat().resideInAnyPackage(
            "dev.tuink.core..",
            "dev.tuink.render..",
            "dev.tuink.highlight..",
            "dev.tuiframe..",
            "dev.tuink.tui.flow..",
            "dev.tuink.tui.input..",
            "dev.tuink.tui.render..",
            "dev.tuink.tui.style..",
            "dev.tuink.tui.terminal..",
            "dev.tuink.tui.ui.."
        );

    // The profiler emits a fixed numeric format; all other frontend text goes
    // through the shared diagnostic boundary before reaching a terminal stream.
    static final ArchRule FRONTENDS_DO_NOT_WRITE_RAW_DIAGNOSTICS = noClasses()
        .that().resideInAnyPackage("dev.tuink.app..", "dev.tuink.tui..")
        .and().doNotHaveFullyQualifiedName("dev.tuink.tui.TerminalEditorApp$FrameProfiler")
        .should().callMethodWhere(new DescribedPredicate<JavaMethodCall>("write raw stream diagnostics") {
            @Override public boolean test(JavaMethodCall call) {
                String owner = call.getTargetOwner().getName();
                String method = call.getTarget().getName();
                return method.equals("printStackTrace")
                    || (owner.equals("java.io.PrintStream") || owner.equals("java.io.PrintWriter"))
                        && java.util.Set.of("print", "println", "printf", "format", "write", "append")
                            .contains(method);
            }
        });

    static final ArchRule JLINE_BACKEND_IS_EDITOR_INDEPENDENT = classes()
        .that().resideInAPackage("dev.tuiframe.jline..")
        .should().onlyDependOnClassesThat().resideInAnyPackage(
            "java..",
            "org.jline..",
            "dev.tuiframe.."
        );

    static final ArchRule PHYSICAL_INPUT_MODEL_IS_SELF_CONTAINED = classes()
        .that().resideInAPackage("dev.tuiframe.input..")
        .should().onlyDependOnClassesThat().resideInAnyPackage(
            "java..",
            "dev.tuiframe.input.."
        );

    static final ArchRule KEY_DECODER_IS_A_SELF_CONTAINED_JLINE_ADAPTER = classes()
        .that().haveFullyQualifiedName(KEY_DECODER)
        .should().onlyDependOnClassesThat().resideInAnyPackage(
            "java..",
            "org.jline..",
            "dev.tuiframe.input..",
            "dev.tuiframe.jline.."
        );

    static final ArchRule SEMANTIC_COMMANDS_DO_NOT_DEPEND_ON_PHYSICAL_INPUT = classes()
        .that().haveFullyQualifiedName("dev.tuink.tui.command.TuinkCommand")
        .should().onlyDependOnClassesThat().resideInAnyPackage(
            "java..",
            "dev.tuink.editor.command..",
            "dev.tuink.tui.command.."
        );

    static final ArchRule EDITOR_INPUT_COMBINES_ONLY_PHYSICAL_EVENTS_AND_SEMANTIC_COMMANDS = classes()
        .that().haveFullyQualifiedName("dev.tuink.tui.input.EditorInput")
        .should().onlyDependOnClassesThat().resideInAnyPackage(
            "java..",
            "dev.tuiframe.input..",
            "dev.tuink.tui.command..",
            "dev.tuink.tui.input.."
        );

    static final ArchRule COMMAND_CONTRACT_IS_SELF_CONTAINED = classes()
        .that().resideInAPackage("dev.tuink.editor.command..")
        .should().onlyDependOnClassesThat().resideInAnyPackage(
            "java..",
            "dev.tuink.editor.command..",
            "dev.tuink.editor.document..",
            "dev.tuink.editor.format..",
            "dev.tuink.editor.search.."
        );

    static final ArchRule DOCUMENT_CONTRACT_IS_SELF_CONTAINED = classes()
        .that().resideInAPackage("dev.tuink.editor.document..")
        .should().onlyDependOnClassesThat().resideInAnyPackage(
            "java..",
            "dev.tuink.editor.document.."
        );

    static final ArchRule BACKEND_PORT_STAYS_PORTABLE = classes()
        .that().resideInAPackage("dev.tuink.editor.backend..")
        .should().onlyDependOnClassesThat().resideInAnyPackage(
            "java..", "dev.tuink.editor.backend..", "dev.tuink.editor.command..",
            "dev.tuink.editor.document..", "dev.tuink.editor.recovery..", "dev.tuink.editor.storage..",
            "dev.tuink.editor.format.."
        );

    static final ArchRule FORMAT_CONTRACT_STAYS_PORTABLE = classes()
        .that().resideInAPackage("dev.tuink.editor.format..")
        .should().onlyDependOnClassesThat().resideInAnyPackage(
            "java..", "dev.tuink.editor.format..", "dev.tuink.editor.storage.."
        );

    static final ArchRule RECOVERY_CONTRACT_STAYS_PORTABLE = classes()
        .that().resideInAPackage("dev.tuink.editor.recovery..")
        .should().onlyDependOnClassesThat().resideInAnyPackage(
            "java..", "dev.tuink.editor.recovery..", "dev.tuink.editor.document..", "dev.tuink.editor.storage.."
        );

    static final ArchRule SEARCH_CONTRACT_IS_SELF_CONTAINED = classes()
        .that().resideInAPackage("dev.tuink.editor.search..")
        .should().onlyDependOnClassesThat().resideInAnyPackage("java..", "dev.tuink.editor.search..");

    static final ArchRule EDITOR_CONTRACT_PACKAGES_ARE_ACYCLIC = slices()
        .matching("dev.tuink.editor.(*)..")
        .should().beFreeOfCycles();

    static final ArchRule RECOVERY_COORDINATION_STAYS_PORTABLE = classes()
        .that().resideInAPackage("dev.tuink.editor.session.recovery..")
        .should().onlyDependOnClassesThat().resideInAnyPackage(
            "java..", "dev.tuink.editor.document..", "dev.tuink.editor.session.recovery.."
        );

    static final ArchRule IO_COORDINATION_STAYS_PORTABLE = classes()
        .that().haveNameMatching("dev\\.tuink\\.editor\\.session\\.document\\.(DocumentIoCoordinator|PreparedDocumentOpen|PreparedDocumentFormat|PreparedDocumentReopen|EditorWorkspace)(?:\\$.*)?")
        .should().onlyDependOnClassesThat().resideInAnyPackage(
            "java..", "dev.tuink.editor.document..", "dev.tuink.editor.storage..", "dev.tuink.editor.session.document..",
            "dev.tuink.editor.command..", "dev.tuink.editor.format.."
        );

    static final ArchRule COORDINATORS_DO_NOT_ACCESS_FILESYSTEM = noClasses()
        .that().resideInAPackage("dev.tuink.editor.session.recovery..")
        .or().haveNameMatching("dev\\.tuink\\.editor\\.session\\.document\\.(DocumentIoCoordinator|PreparedDocumentOpen|PreparedDocumentFormat|PreparedDocumentReopen|EditorWorkspace)(?:\\$.*)?")
        .should().dependOnClassesThat().resideInAPackage("java.nio.file..");

    static final ArchRule LANGUAGE_CONTRACT_IS_SELF_CONTAINED = classes()
        .that().resideInAPackage("dev.tuink.editor.language..")
        .should().onlyDependOnClassesThat().resideInAnyPackage(
            "java..",
            "dev.tuink.editor.language.."
        );

    static final ArchRule EDITOR_SESSION_STAYS_FRONTEND_INDEPENDENT = classes()
        .that().resideInAPackage("dev.tuink.editor.session..")
        .should().onlyDependOnClassesThat().resideInAnyPackage(
            "java..",
            "dev.tuink.core..",
            "dev.tuink.editor.command..",
            "dev.tuink.editor.document..",
            "dev.tuink.editor.format..",
            "dev.tuink.editor.backend..",
            "dev.tuink.editor.recovery..",
            "dev.tuink.editor.diff..",
            "dev.tuink.editor.storage..",
            "dev.tuink.editor.session.."
        );

    static final ArchRule KEYMAP_LAYER_STAYS_EDITOR_INDEPENDENT = classes()
        .that().resideInAPackage("dev.tuink.tui.keymap..")
        .should().onlyDependOnClassesThat().resideInAnyPackage(
            "java..",
            "dev.tuink.editor.command..",
            "dev.tuink.tui.command..",
            "dev.tuiframe.input..",
            "dev.tuink.tui.keymap.."
        );

    static final ArchRule GENERIC_KEYMAP_DOES_NOT_IMPORT_PHYSICAL_INPUT = classes()
        .that().haveFullyQualifiedName("dev.tuink.tui.keymap.Keymap")
        .should().onlyDependOnClassesThat().resideInAnyPackage(
            "java..",
            "dev.tuink.editor.command..",
            "dev.tuink.tui.keymap.."
        );

    static final ArchRule TUINK_KEYMAP_DOES_NOT_IMPORT_DECODER_OR_RESOLVED_INPUT = noClasses()
        .that().haveFullyQualifiedName("dev.tuink.tui.keymap.TuinkKeymap")
        .should().dependOnClassesThat().resideInAnyPackage(
            "dev.tuiframe.jline..",
            "dev.tuink.tui.input.."
        );

    static final ArchRule EVENT_LOOP_IS_EDITOR_INDEPENDENT = classes()
        .that().resideInAPackage("dev.tuiframe.runtime..")
        .should().onlyDependOnClassesThat().resideInAnyPackage(
            "java..",
            "dev.tuiframe.runtime.."
        );

    static final ArchRule FRAME_LOOP_ORCHESTRATION_STAYS_IN_RUNTIME = noClasses()
        .that().resideOutsideOfPackage("dev.tuiframe.runtime..")
        .should().callMethod(
            "dev.tuiframe.runtime.TuiEventLoop",
            "beginFrame"
        )
        .orShould().callMethod(
            "dev.tuiframe.runtime.TuiEventLoop",
            "endFrame",
            "dev.tuiframe.runtime.TuiEventLoop$FrameTicket",
            "boolean"
        )
        .orShould().callMethod(
            "dev.tuiframe.runtime.TuiEventLoop",
            "poll",
            "dev.tuiframe.runtime.TuiEventLoop$Deadline"
        );

    static final ArchRule WIDGET_LAYER_STAYS_EDITOR_INDEPENDENT = classes()
        .that().resideInAPackage("dev.tuiframe.widget..")
        .should().onlyDependOnClassesThat().resideInAnyPackage(
            "java..",
            "dev.tuiframe.."
        );

    static final ArchRule WIDGET_LAYER_STAYS_FILESYSTEM_FREE = noClasses()
        .that().resideInAPackage("dev.tuiframe.widget..")
        .should().dependOnClassesThat().resideInAnyPackage(
            "java.io..",
            "java.nio.file.."
        );

    static final ArchRule TUI_GEOMETRY_IS_SELF_CONTAINED = classes()
        .that().resideInAPackage("dev.tuiframe.geometry..")
        .should().onlyDependOnClassesThat().resideInAnyPackage(
            "java..",
            "dev.tuiframe.geometry.."
        );

    static final ArchRule CELL_STYLE_IS_SELF_CONTAINED = classes()
        .that().haveNameMatching("dev\\.tuiframe\\.style\\.Cell(?:Color|Style)(?:\\$.*)?")
        .should().onlyDependOnClassesThat().haveNameMatching(
            "(?:\\[+[ZBCSIJFD]|\\[+Ldev\\.tuiframe\\.style\\.Cell(?:Color|Style);|"
                + "java\\..*|dev\\.tuiframe\\.style\\.Cell(?:Color|Style)(?:\\$.*)?)"
        );

    static final ArchRule CELL_MODEL_DOES_NOT_DEPEND_ON_EDITOR_RENDER = noClasses()
        .that().haveNameMatching(
            "dev\\.tuiframe\\.cell\\.(?:Cell|CellBuffer|CellText|Canvas)(?:\\$.*)?"
        )
        .should().dependOnClassesThat().resideInAnyPackage(
            "dev.tuink.core..",
            "dev.tuink.render..",
            "dev.tuink.tui.."
        );

    static final ArchRule SCREEN_MODEL_STAYS_FOUNDATION_ONLY = classes()
        .that().resideInAPackage("dev.tuiframe.screen..")
        .should().onlyDependOnClassesThat().haveNameMatching(
            "(?:\\[+[ZBCSIJFD]|java\\..*|dev\\.tuiframe\\.geometry\\..*|"
                + "dev\\.tuiframe\\.screen\\..*|dev\\.tuiframe\\.cell\\.CellBuffer(?:\\$.*)?)"
        );

    static final ArchRule SCREEN_PRESENTATION_STAYS_EDITOR_INDEPENDENT = classes()
        .that().resideInAPackage("dev.tuiframe.jline.presentation..")
        .should().onlyDependOnClassesThat().resideInAnyPackage(
            "java..",
            "dev.tuiframe..",
            "dev.tuiframe.jline.presentation.."
        );

    static final ArchRule TERMINAL_EDITOR_APP_DOES_NOT_BYPASS_SCREEN_PRESENTER = noClasses()
        .that().haveFullyQualifiedName("dev.tuink.tui.TerminalEditorApp")
        .should().dependOnClassesThat().haveNameMatching(
            "(?:dev\\.tuiframe\\.jline\\.presentation\\.AnsiDiff|"
                + "dev\\.tuink\\.tui\\.ui\\.TerminalCursorController)"
        );

    static final ArchRule JLINE_STAYS_IN_BACKEND = noClasses()
        .that().resideOutsideOfPackage("dev.tuiframe.jline..")
        .should().dependOnClassesThat().resideInAPackage("org.jline..");

    // editor-contract and editor-session share dev.tuink.editor, but are separate
    // modules: core -> contract and session -> core form an acyclic module graph.
    private static final SliceAssignment TUINK_MODULES = new SliceAssignment() {
        @Override
        public SliceIdentifier getIdentifierOf(JavaClass javaClass) {
            String prefix = "dev.tuink.";
            String packageName = javaClass.getPackageName();
            if (!packageName.startsWith(prefix)) return SliceIdentifier.ignore();
            String relative = packageName.substring(prefix.length());
            if (relative.equals("editor.session") || relative.startsWith("editor.session.")) {
                return SliceIdentifier.of("editor-session");
            }
            for (String contractPackage : new String[] {"editor.command", "editor.document", "editor.language",
                "editor.search", "editor.backend", "editor.recovery",
                "editor.highlight", "editor.diff", "editor.storage", "editor.format"}) {
                if (relative.equals(contractPackage) || relative.startsWith(contractPackage + ".")) {
                    return SliceIdentifier.of("editor-contract");
                }
            }
            // Retain the original partition for every other TUInk namespace,
            // including future packages, so no module disappears from the rule.
            int separator = relative.indexOf('.');
            return SliceIdentifier.of(separator < 0 ? relative : relative.substring(0, separator));
        }

        @Override
        public String getDescription() {
            return "TUInk modules with separate editor-contract and editor-session";
        }
    };

    static final ArchRule TOP_LEVEL_SLICES_ARE_FREE_OF_CYCLES = slices()
        .assignedFrom(TUINK_MODULES)
        .should().beFreeOfCycles();

    static final ArchRule TUI_SUBPACKAGE_SLICES_ARE_FREE_OF_CYCLES = slices()
        .matching("dev.tuink.tui.(*)..")
        .should().beFreeOfCycles();

    static final ArchRule TERMINAL_SUBPACKAGE_SLICES_ARE_FREE_OF_CYCLES = slices()
        .matching("dev.tuiframe.(*)..")
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
        DIFF_PROVIDER_STAYS_BEHIND_ITS_CONTRACT.check(PRODUCTION_CLASSES);
        JVM_STORAGE_DEPENDS_ONLY_ON_SHARED_SERVICES.check(PRODUCTION_CLASSES);
        SHARED_ENGINE_DOES_NOT_ACCESS_FILESYSTEM.check(PRODUCTION_CLASSES);
        STORAGE_CONTRACT_STAYS_PORTABLE.check(PRODUCTION_CLASSES);
        TUI_DOES_NOT_DEPEND_ON_APPLICATION_ENTRYPOINT.check(PRODUCTION_CLASSES);
        FILE_TREE_MODEL_STAYS_TERMINAL_INDEPENDENT.check(PRODUCTION_CLASSES);
        APP_USES_ONLY_THE_TUI_FACADE.check(PRODUCTION_CLASSES);
        FRONTENDS_DO_NOT_WRITE_RAW_DIAGNOSTICS.check(PRODUCTION_CLASSES);
        TOP_LEVEL_SLICES_ARE_FREE_OF_CYCLES.check(PRODUCTION_CLASSES);
        TUI_SUBPACKAGE_SLICES_ARE_FREE_OF_CYCLES.check(PRODUCTION_CLASSES);
        TERMINAL_SUBPACKAGE_SLICES_ARE_FREE_OF_CYCLES.check(PRODUCTION_CLASSES);
    }

    @Test
    void terminalFoundationStaysEditorIndependent() {
        JLINE_BACKEND_IS_EDITOR_INDEPENDENT.check(PRODUCTION_CLASSES);
        EVENT_LOOP_IS_EDITOR_INDEPENDENT.check(PRODUCTION_CLASSES);
        FRAME_LOOP_ORCHESTRATION_STAYS_IN_RUNTIME.check(PRODUCTION_CLASSES);
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
        SEMANTIC_COMMANDS_DO_NOT_DEPEND_ON_PHYSICAL_INPUT.check(PRODUCTION_CLASSES);
        EDITOR_INPUT_COMBINES_ONLY_PHYSICAL_EVENTS_AND_SEMANTIC_COMMANDS.check(PRODUCTION_CLASSES);
        COMMAND_CONTRACT_IS_SELF_CONTAINED.check(PRODUCTION_CLASSES);
        DOCUMENT_CONTRACT_IS_SELF_CONTAINED.check(PRODUCTION_CLASSES);
        LANGUAGE_CONTRACT_IS_SELF_CONTAINED.check(PRODUCTION_CLASSES);
        EDITOR_SESSION_STAYS_FRONTEND_INDEPENDENT.check(PRODUCTION_CLASSES);
        BACKEND_PORT_STAYS_PORTABLE.check(PRODUCTION_CLASSES);
        FORMAT_CONTRACT_STAYS_PORTABLE.check(PRODUCTION_CLASSES);
        RECOVERY_CONTRACT_STAYS_PORTABLE.check(PRODUCTION_CLASSES);
        SEARCH_CONTRACT_IS_SELF_CONTAINED.check(PRODUCTION_CLASSES);
        EDITOR_CONTRACT_PACKAGES_ARE_ACYCLIC.check(PRODUCTION_CLASSES);
        RECOVERY_COORDINATION_STAYS_PORTABLE.check(PRODUCTION_CLASSES);
        IO_COORDINATION_STAYS_PORTABLE.check(PRODUCTION_CLASSES);
        COORDINATORS_DO_NOT_ACCESS_FILESYSTEM.check(PRODUCTION_CLASSES);
        KEYMAP_LAYER_STAYS_EDITOR_INDEPENDENT.check(PRODUCTION_CLASSES);
        GENERIC_KEYMAP_DOES_NOT_IMPORT_PHYSICAL_INPUT.check(PRODUCTION_CLASSES);
        TUINK_KEYMAP_DOES_NOT_IMPORT_DECODER_OR_RESOLVED_INPUT.check(PRODUCTION_CLASSES);
    }
}
