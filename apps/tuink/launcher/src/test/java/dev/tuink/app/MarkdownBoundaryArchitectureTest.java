package dev.tuink.app;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

/** Guards the portable preview contracts and the replaceable parser/provider boundaries. */
class MarkdownBoundaryArchitectureTest {
    private static final String[] PROVIDERS = {
        "dev.tuink.markdown.commonmark..",
        "dev.tuink.markdown.math.builtin..",
        "dev.tuink.markdown.mermaid.builtin.."
    };
    private static final JavaClasses PRODUCTION = new ClassFileImporter()
        .withImportOption(new ImportOption.DoNotIncludeTests())
        .importPackages("dev.tuink", "dev.tuiframe");

    @Test
    void verificationImportsTheActualPreviewAndProviderImplementations() {
        // An absent module must fail this gate rather than make a dependency rule vacuous.
        for (String name : new String[] {
                "dev.tuink.markdown.MarkdownDocument",
                "dev.tuink.markdown.preview.PreviewCoordinator",
                "dev.tuink.markdown.commonmark.CommonmarkMarkdownParser",
                "dev.tuink.markdown.math.builtin.BuiltinMathProvider",
                "dev.tuink.markdown.mermaid.builtin.BuiltinMermaidProvider",
                "dev.tuink.render.markdown.MarkdownLayout",
                "dev.tuink.render.markdown.MathLayout",
                "dev.tuink.render.markdown.MermaidLayout"}) {
            assertNotNull(PRODUCTION.get(name), name);
        }
    }

    @Test
    void editorAndTerminalToolkitDoNotDependOnTheMarkdownFeature() {
        noClasses()
            .that().resideInAnyPackage("dev.tuink.core..", "dev.tuink.editor..",
                "dev.tuink.unicode..", "dev.tuiframe..")
            .should().dependOnClassesThat().resideInAPackage("dev.tuink.markdown..")
            .because("Markdown is an optional frontend consumer of document contracts")
            .check(PRODUCTION);
    }

    @Test
    void terminalLayoutDependsOnNeutralModelsRatherThanBuiltinEngines() {
        noClasses()
            .that().resideInAPackage("dev.tuink.render..")
            .should().dependOnClassesThat().resideInAnyPackage(
                "org.commonmark..", PROVIDERS[0], PROVIDERS[1], PROVIDERS[2])
            .because("the frontend composition supplies replaceable Markdown, Math and Mermaid providers")
            .check(PRODUCTION);
    }

    @Test
    void sharedMarkdownModelsAndCoordinationRemainPortable() {
        classes()
            .that().resideInAPackage("dev.tuink.markdown..")
            .and().resideOutsideOfPackages(PROVIDERS)
            .should().onlyDependOnClassesThat().resideInAnyPackage(
                "java..", "dev.tuink.markdown..", "dev.tuink.editor.document..")
            .because("Android and desktop consume the same immutable feature API")
            .check(PRODUCTION);
        noClasses()
            .that().resideInAPackage("dev.tuink.markdown..")
            .and().resideOutsideOfPackages(PROVIDERS)
            .should().dependOnClassesThat().resideInAnyPackage(
                "org.commonmark..", PROVIDERS[0], PROVIDERS[1], PROVIDERS[2])
            .because("provider implementations do not belong in neutral models or public contracts")
            .check(PRODUCTION);
    }

    @Test
    void builtinProvidersDoNotAcquireAPlatformOrMutableEditorDependency() {
        classes()
            .that().resideInAnyPackage(PROVIDERS)
            .should().onlyDependOnClassesThat().resideInAnyPackage(
                "java..", "dev.tuink.markdown..", "dev.tuink.editor.document..", "org.commonmark..")
            .because("provider work is detached from UI, filesystem and mutable document owners")
            .check(PRODUCTION);
    }

    @Test
    void portableMarkdownCannotAcquireFilesystemDesktopOrProcessAccess() {
        noClasses().that().resideInAPackage("dev.tuink.markdown..")
            .should().dependOnClassesThat().resideInAnyPackage(
                "java.nio.file..", "java.awt..", "javax.swing..", "javafx..")
            .check(PRODUCTION);
        for (String type : new String[] {"java.io.File", "java.io.RandomAccessFile", "java.lang.ProcessBuilder"}) {
            noClasses().that().resideInAPackage("dev.tuink.markdown..")
                .should().dependOnClassesThat().haveFullyQualifiedName(type)
                .check(PRODUCTION);
        }
    }
}
