package dev.tuink.app;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Member;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class EditorSessionApiArchitectureTest {
    private static final String CORE_PACKAGE = "dev.tuink.core.";

    @Test
    void rawSaveCapturesRemainInternalToTheSessionCoordinator() throws ClassNotFoundException {
        Class<?> session = Class.forName("dev.tuink.editor.session.document.EditorDocumentSession");
        Set<String> internals = Set.of("prepareSave", "prepareSaveAsInput", "approveSaveAs", "completeSaved");
        for (var method : session.getDeclaredMethods()) {
            if (internals.contains(method.getName())) {
                assertTrue(!Modifier.isPublic(method.getModifiers()) && !Modifier.isProtected(method.getModifiers()),
                    () -> "Raw save protocol bypasses the coordinator: " + method);
            }
        }
    }

    @Test
    void workspaceOwnsMembershipAndItsSessionHandshakesRemainInternal() throws ClassNotFoundException {
        Class<?> session = Class.forName("dev.tuink.editor.session.document.EditorDocumentSession");
        Set<String> handshakes = Set.of("claimWorkspace", "releaseWorkspace", "detachWorkspaceView");
        for (var method : session.getDeclaredMethods()) {
            if (handshakes.contains(method.getName())) {
                assertTrue(!Modifier.isPublic(method.getModifiers()) && !Modifier.isProtected(method.getModifiers()),
                    () -> "Workspace lifetime can be bypassed through a public handshake: " + method);
            }
        }
        Class<?> context = Class.forName("dev.tuink.tui.TerminalEditorApp$SessionContext");
        Set<String> duplicated = Set.of("java.util.List", "dev.tuink.core.TextModel", "dev.tuink.core.EditorState",
            "dev.tuink.core.EditorEngine", "dev.tuink.render.RenderPlanBuilder");
        for (var field : context.getDeclaredFields()) {
            if (!Modifier.isStatic(field.getModifiers())) {
                assertTrue(!duplicated.contains(field.getType().getName()) && field.getType() != int.class,
                    () -> "Frontend context duplicates workspace membership or active projection: " + field);
            }
        }
    }

    @Test
    void editorSessionPublicApiDoesNotExposeMutableCoreTypes() throws ClassNotFoundException {
        JavaClasses production = new ClassFileImporter()
            .withImportOption(new ImportOption.DoNotIncludeTests())
            .importPackages("dev.tuink.editor.session");
        List<String> violations = new ArrayList<>();

        for (var imported : production) {
            Class<?> type = Class.forName(imported.getName(), false, getClass().getClassLoader());
            if (!Modifier.isPublic(type.getModifiers())) {
                continue;
            }
            inspectType(type.getName() + " extends", type.getGenericSuperclass(), violations);
            for (Type contract : type.getGenericInterfaces()) {
                inspectType(type.getName() + " implements", contract, violations);
            }
            for (var field : type.getDeclaredFields()) {
                inspect(field, field.getGenericType(), violations);
            }
            for (var constructor : type.getDeclaredConstructors()) {
                for (Type parameter : constructor.getGenericParameterTypes()) {
                    inspect(constructor, parameter, violations);
                }
                for (Type exception : constructor.getGenericExceptionTypes()) {
                    inspect(constructor, exception, violations);
                }
            }
            for (var method : type.getDeclaredMethods()) {
                inspect(method, method.getGenericReturnType(), violations);
                for (Type parameter : method.getGenericParameterTypes()) {
                    inspect(method, parameter, violations);
                }
                for (Type exception : method.getGenericExceptionTypes()) {
                    inspect(method, exception, violations);
                }
            }
        }

        assertTrue(
            violations.isEmpty(),
            () -> "editor-session portable API exposes mutable core types or its composition bridge:\n  "
                + String.join("\n  ", violations)
        );
    }

    @Test
    void frontendCompositionCannotExportOrBypassTheDocumentCommandGate() {
        JavaClasses production = new ClassFileImporter()
            .withImportOption(new ImportOption.DoNotIncludeTests())
            .importPackages("dev.tuink");
        noClasses()
            .that().resideOutsideOfPackage("dev.tuink.core..")
            .and().resideOutsideOfPackage("dev.tuink.editor.session..")
            .and().doNotHaveFullyQualifiedName("dev.tuink.tui.TuiDocumentSession")
            .should().dependOnClassesThat()
            .haveNameMatching("dev\\.tuink\\.core\\.CoreDocumentBackend(?:\\$.*)?")
            .because("only frontend composition connects an owned backend; document handlers use the session")
            .check(production);

        Set<String> mutations = Set.of("insertChar", "insertInput", "insertTab", "insertNewline", "backspace",
            "deleteForward", "toggleLineComment", "copySelection", "copyCurrentLine", "cutSelection", "cutCurrentLine",
            "pasteClipboard", "pasteText", "selectAll", "undo", "redo", "applyTextReplacements", "executeCommand",
            "findNext", "findPrevious", "replaceCurrentOrNext", "replaceAll", "moveCursorTo", "navigateTo",
            "beginSelectionAt", "extendSelectionTo", "restoreSelection", "finishSelection", "selectWordAt",
            "moveLeft", "moveRight", "moveWordLeft", "moveWordRight", "moveHome", "moveEnd", "moveHomeSmart",
            "moveEndSmart", "moveDocumentStart", "moveDocumentEnd", "open", "openExisting", "openPrepared",
            "save", "saveAs", "saveAsUtf8", "prepareSave", "prepareSaveAs", "prepareSaveAsInput", "approveSaveAs",
            "completeSaved", "restoreRecoverySnapshot");
        Set<String> modelMutations = Set.of("replaceLines", "replaceRange", "setLine", "insertLine", "deleteLine",
            "load", "applyPreparedLoad", "save", "saveIfUnchanged", "completeSave", "restoreFileBaseline",
            "publishReplacement", "beginLocalEdit");
        List<String> bypasses = new ArrayList<>();
        for (var origin : production) {
            String name = origin.getName();
            if (!name.startsWith("dev.tuink.tui.") && !name.startsWith("dev.tuink.render.")) continue;
            for (var call : origin.getMethodCallsFromSelf()) {
                String owner = call.getTarget().getOwner().getName();
                String method = call.getTarget().getName();
                if (owner.equals("dev.tuink.core.EditorEngine") && mutations.contains(method)
                    || owner.equals("dev.tuink.core.TextModel") && modelMutations.contains(method)) {
                    bypasses.add(call.getDescription());
                }
            }
        }
        assertTrue(bypasses.isEmpty(), () -> "Frontend bypasses the session mutation gate:\n  " + String.join("\n  ", bypasses));
    }

    private static void inspect(Member member, Type type, List<String> violations) {
        if (!Modifier.isPublic(member.getModifiers())
            && !Modifier.isProtected(member.getModifiers())) {
            return;
        }
        if (mentionsCore(type)) {
            violations.add(member.getDeclaringClass().getName() + "#" + member.getName()
                + " -> " + type.getTypeName());
        }
    }

    private static void inspectType(String owner, Type type, List<String> violations) {
        if (type != null && mentionsCore(type)) {
            violations.add(owner + " -> " + type.getTypeName());
        }
    }

    private static boolean mentionsCore(Type type) {
        if (type instanceof Class<?> raw) {
            return raw.getName().startsWith(CORE_PACKAGE)
                || raw.isArray() && mentionsCore(raw.getComponentType());
        }
        if (type instanceof ParameterizedType parameterized) {
            if (mentionsCore(parameterized.getRawType())) {
                return true;
            }
            for (Type argument : parameterized.getActualTypeArguments()) {
                if (mentionsCore(argument)) {
                    return true;
                }
            }
            return false;
        }
        if (type instanceof GenericArrayType array) {
            return mentionsCore(array.getGenericComponentType());
        }
        if (type instanceof WildcardType wildcard) {
            return anyMentionsCore(wildcard.getLowerBounds())
                || anyMentionsCore(wildcard.getUpperBounds());
        }
        if (type instanceof TypeVariable<?> variable) {
            return anyMentionsCore(variable.getBounds());
        }
        return false;
    }

    private static boolean anyMentionsCore(Type[] types) {
        for (Type type : types) {
            if (mentionsCore(type)) {
                return true;
            }
        }
        return false;
    }
}
