package dev.tuink.app;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Guards the shared mechanism and removal of alternate routes; behavioral tests exercise event ownership. */
class SharedInteractionArchitectureTest {
    private static final String INTERACTION = "dev.tuink.render.surface.TextSurfaceInteraction";
    private static final String POINTER = "dev.tuink.render.surface.TextSurfacePointer";
    private static final String GEOMETRY = "dev.tuink.render.surface.TextSurfaceGeometry";
    private static final String CONTENT = "dev.tuink.render.surface.TextSurfaceContent";
    private static final String CLOSE = "dev.tuink.editor.session.application.ApplicationCloseWorkflow";
    private static final String COMMAND = "dev.tuink.tui.WindowInput$Command";
    private static final String WINDOW_TEXT = "dev.tuink.tui.render.WindowTextPainter";
    private static final String FIELD_INPUT = "dev.tuink.tui.FieldInputDispatcher";
    private static final String FIELD_CLIPBOARD = "dev.tuink.tui.FieldClipboard";
    private static final JavaClasses PRODUCTION = new ClassFileImporter()
        .withImportOption(new ImportOption.DoNotIncludeTests())
        .importPackages("dev.tuink", "dev.tuiframe");

    @Test
    void verificationIncludesTheSharedMechanismsAndTheirActualConsumers() {
        // An absent module must fail rather than turn a dependency rule into an empty check.
        for (String name : new String[] {
                INTERACTION, POINTER, GEOMETRY, CONTENT, CLOSE, COMMAND, WINDOW_TEXT, FIELD_INPUT, FIELD_CLIPBOARD,
                "dev.tuink.render.EditorViewCoordinates",
                "dev.tuink.render.EditorViewNavigation",
                "dev.tuink.render.markdown.MarkdownTextSurface",
                "dev.tuink.tui.ui.EditorView",
                "dev.tuink.tui.ui.ViewportController",
                "dev.tuink.tui.PreviewSelection",
                "dev.tuink.tui.MarkdownPreviewState",
                "dev.tuink.tui.EditorWindowController"}) {
            assertNotNull(PRODUCTION.get(name), name);
        }
    }

    @Test
    void sharedTextInteractionRemainsIndependentOfDocumentsAndFrontendState() {
        classes()
            .that().resideInAPackage("dev.tuink.render.surface..")
            .should().onlyDependOnClassesThat().resideInAnyPackage(
                "java..",
                "dev.tuink.render.surface..",
                "dev.tuiframe.geometry..",
                "dev.tuiframe.input..",
                "dev.tuiframe.scroll..",
                "dev.tuiframe.text..")
            .because("content and geometry adapters supply positions; the common controller owns no editor or Markdown state")
            .check(PRODUCTION);
    }

    @Test
    void sourceAndPreviewInvokeTheSameNavigationAndPointerMechanisms() {
        requireCall("dev.tuink.render.EditorViewNavigation", INTERACTION);
        requireCall("dev.tuink.tui.PreviewSelection", INTERACTION);
        requireCall("dev.tuink.tui.ui.EditorView", POINTER);
        requireCall("dev.tuink.tui.ui.ViewportController", INTERACTION);
        requireCall("dev.tuink.tui.MarkdownPreviewState", INTERACTION);
        requireCall("dev.tuink.tui.MarkdownPreviewState", POINTER);
        requireDependency("dev.tuink.render.EditorViewCoordinates", GEOMETRY);
        requireDependency("dev.tuink.render.markdown.MarkdownTextSurface", GEOMETRY);
        requireDependency("dev.tuink.render.markdown.MarkdownTextSurface", CONTENT);
    }

    @Test
    void sourceAndPreviewPaintWindowTextThroughTheSameBoundary() {
        requireCall("dev.tuink.tui.render.RenderRasterizer", WINDOW_TEXT);
        requireCall("dev.tuink.tui.TerminalEditorApp", WINDOW_TEXT);
        noClasses()
            .that().haveNameMatching("dev\\.tuink\\.tui\\.render\\.WindowTextPainter(?:\\$.*)?")
            .should().dependOnClassesThat().resideInAnyPackage(
                "dev.tuink.core..", "dev.tuink.editor.session..")
            .because("window chrome receives prepared text and styles rather than a document or editing session")
            .check(PRODUCTION);
    }

    @Test
    void fieldsShareTheClipboardAndAdmissionEntryPoint() {
        requireCall(FIELD_INPUT, FIELD_CLIPBOARD);
        for (String owner : new String[] {
                "dev.tuink.tui.SearchInputRouter", "dev.tuink.tui.SaveAsInputRouter",
                "dev.tuink.tui.OpenFileInputRouter", "dev.tuink.tui.EditorWindowController"}) {
            requireCall(owner, FIELD_INPUT);
        }
        noClasses()
            .that().doNotHaveFullyQualifiedName(FIELD_INPUT)
            .should().callMethod(FIELD_CLIPBOARD, "handle",
                "dev.tuink.tui.command.TuinkCommand", "dev.tuiframe.widget.TextField", "dev.tuink.core.EditorClipboard")
            .because("field owners supply focus and intent; editing, admission and clipboard go through one dispatcher")
            .check(PRODUCTION);
    }

    @Test
    void applicationCloseDecisionsRemainPortableAndDoNotAcquireAWindowOrMutableEngine() {
        classes()
            .that().haveNameMatching(
                "dev\\.tuink\\.editor\\.session\\.application\\.ApplicationCloseWorkflow(?:\\$.*)?")
            .should().onlyDependOnClassesThat().resideInAnyPackage(
                "java..", "dev.tuink.editor.session.application..")
            .because("the host supplies opaque recipient/operation identities and executes the returned effects")
            .check(PRODUCTION);
    }

    @Test
    void finalSemanticCommandsCannotCarryExecutablePhysicalInput() {
        noClasses()
            .that().haveFullyQualifiedName(COMMAND)
            .should().dependOnClassesThat().resideInAPackage("dev.tuiframe.input..")
            .because("a resolved command cannot replay its originating key, text or pointer event")
            .check(PRODUCTION);
    }

    @Test
    void previewRenderingAndInteractionRemainIndependentOfMutableEditorOwners() {
        noClasses()
            .that().haveNameMatching("dev\\.tuink\\.tui\\.(?:PreviewSelection|MarkdownPreviewState|"
                + "PreviewPresentationView|MarkdownPreviewPainter)(?:\\$.*)?")
            .should().dependOnClassesThat()
            .haveNameMatching("dev\\.tuink\\.core\\.(?:EditorEngine|EditorState|TextModel)(?:\\$.*)?")
            .because("a frozen projection must stay usable after retiring its editing session and history")
            .check(PRODUCTION);
    }

    @Test
    void removedInputContractsAndGeneralPreviewEntryPointsCannotReturn() {
        Set<String> removedTypes = Set.of(
            "dev.tuink.tui.input.EditorInput",
            "dev.tuink.tui.WindowCloseWorkflow");
        Set<String> removedMethods = Set.of(
            "handlePreviewInput", "handlePreviewApplicationCommand", "handlePreviewSearchInput");
        List<String> remaining = new ArrayList<>();
        for (JavaClass type : PRODUCTION) {
            for (String removed : removedTypes) {
                if (type.getName().equals(removed) || type.getName().startsWith(removed + "$")) {
                    remaining.add(type.getName());
                }
            }
            for (var method : type.getMethods()) {
                if (removedMethods.contains(method.getName())) remaining.add(method.getFullName());
            }
        }
        assertTrue(remaining.isEmpty(), () -> "Alternate general input routes remain:\n  " + String.join("\n  ", remaining));
    }

    private static void requireCall(String originName, String targetName) {
        JavaClass origin = PRODUCTION.get(originName);
        assertTrue(origin.getMethodCallsFromSelf().stream()
                .anyMatch(call -> call.getTargetOwner().getName().equals(targetName)),
            () -> originName + " does not invoke the shared mechanism " + targetName);
    }

    private static void requireDependency(String originName, String targetName) {
        JavaClass origin = PRODUCTION.get(originName);
        assertTrue(origin.getDirectDependenciesFromSelf().stream()
                .anyMatch(dependency -> dependency.getTargetClass().getName().equals(targetName)),
            () -> originName + " does not consume the shared contract " + targetName);
    }
}
