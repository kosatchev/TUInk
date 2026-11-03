package dev.tuink.app;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Boundaries introduced by the editor-window and shared-capture migration. */
class EditorViewBoundaryArchitectureTest {
    private static final JavaClasses PRODUCTION = new ClassFileImporter()
        .withImportOption(new ImportOption.DoNotIncludeTests())
        .importPackages("dev.tuink", "dev.tuiframe");

    @Test
    void coordinateAndNavigationKernelUsesOnlyCoreAndCoordinateProjection() {
        classes()
            .that().haveNameMatching("dev\\.tuink\\.render\\.EditorView(?:Coordinates|Navigation)(?:\\$.*)?")
            .should().onlyDependOnClassesThat()
            .haveNameMatching("(?:java\\..*|dev\\.tuink\\.core\\..*|"
                + "dev\\.tuink\\.render\\.EditorView(?:Coordinates|Navigation)(?:\\$.*)?)")
            .check(PRODUCTION);
    }

    @Test
    void terminalLayoutCacheHasExplicitModelAdapters() {
        noClasses()
            .that().resideInAPackage("dev.tuink..")
            .and().haveNameNotMatching("(?:dev\\.tuink\\.render\\.TerminalEditorTextLayout|dev\\.tuink\\.render\\.markdown\\.MarkdownGeometry)(?:\\$.*)?")
            .should().dependOnClassesThat()
            // Refcounted metric leases are a consumer contract, not access to the cache.
            .haveNameMatching("dev\\.tuiframe\\.text\\.TerminalTextLayoutCache(?:\\$(?!MetricsHandle$).*)?")
            .because("editor and immutable Markdown layouts each have one explicit terminal geometry adapter")
            .check(PRODUCTION);
    }

    @Test
    void sharedCaptureDeliveryHasOneTuinkCoordinator() {
        noClasses()
            .that().resideInAPackage("dev.tuink..")
            .and().haveNameNotMatching("dev\\.tuink\\.tui\\.WindowPointerCapture(?:\\$.*)?")
            .should().dependOnClassesThat()
            .haveNameMatching("dev\\.tuiframe\\.widget\\.PointerCaptureManager(?:\\$.*)?")
            .because("dialog adapters supply current bindings instead of retaining separate capture owners")
            .check(PRODUCTION);
    }

    @Test
    void documentMetadataAndViewPositionDoNotRetainEachOthersMutableState() {
        classes()
            .that().haveFullyQualifiedName("dev.tuink.core.EditorDocumentState")
            .should().onlyDependOnClassesThat()
            .haveNameMatching("(?:java\\..*|dev\\.tuink\\.core\\.EditorDocumentState|"
                + "dev\\.tuink\\.editor\\.storage\\..*)")
            .check(PRODUCTION);
        classes()
            .that().haveFullyQualifiedName("dev.tuink.core.EditorViewState")
            .should().onlyDependOnClassesThat()
            .haveNameMatching("(?:java\\..*|dev\\.tuink\\.core\\.(?:EditorViewState|Position))")
            .check(PRODUCTION);
    }

    @Test
    void engineAppliesLogicalNavigationTargetsInsteadOfOwningViewportNavigationEntryPoints() {
        Set<String> viewOperations = Set.of("moveUp", "moveDown", "pageUp", "pageDown", "ensureCursorVisible");
        var forbidden = PRODUCTION.get("dev.tuink.core.EditorEngine").getMethods().stream()
            .filter(method -> viewOperations.contains(method.getName()))
            .map(method -> method.getFullName())
            .sorted()
            .toList();
        assertTrue(forbidden.isEmpty(), () -> "viewport navigation belongs to EditorViewNavigation: " + forbidden);
    }
}
