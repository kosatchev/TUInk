package dev.tuink.app;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

/** Keeps editing decisions and application workflows independent of their callers. */
class EditingWorkflowArchitectureTest {
    private static final JavaClasses PRODUCTION = new ClassFileImporter()
        .withImportOption(new ImportOption.DoNotIncludeTests())
        .importPackages("dev.tuink", "dev.tuiframe");

    @Test
    void typingDecisionsCannotAccessMutableDocumentOrViewOwners() {
        noClasses()
            .that().haveNameMatching("dev\\.tuink\\.core\\.(?:IndentationDecisions|MarkdownListDecisions|"
                + "MarkdownBlockContext)(?:\\$.*)?")
            .should().dependOnClassesThat()
            .haveNameMatching("dev\\.tuink\\.core\\.(?:EditorEngine|EditorDocument|EditorState|TextModel)(?:\\$.*)?")
            .because("assist rules propose edits through read-only text access; the engine applies them")
            .check(PRODUCTION);
    }

    @Test
    void applicationWorkflowsDoNotDependOnWindowHostOrToolkitControls() {
        noClasses()
            .that().haveNameMatching("dev\\.tuink\\.tui\\.(?:SearchCommandHandler|SearchSelection|SearchPresentation|"
                + "SaveWorkflow|SaveTargetPolicy|FilePickerOpenWorkflow)(?:\\$.*)?")
            .should().dependOnClassesThat()
            .haveNameMatching("(?:dev\\.tuink\\.tui\\.TerminalEditorApp(?:\\$.*)?|dev\\.tuiframe\\..*)")
            .because("the window applies workflow results to focus, widgets and rendering")
            .check(PRODUCTION);
    }
}
