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
import org.junit.jupiter.api.Test;

class EditorSessionApiArchitectureTest {
    private static final String CORE_PACKAGE = "dev.tuink.core.";
    private static final String CORE_BRIDGE = "dev.tuink.editor.session.document.CoreSessionBridge";

    @Test
    void editorSessionPublicApiDoesNotExposeMutableCoreTypes() throws ClassNotFoundException {
        JavaClasses production = new ClassFileImporter()
            .withImportOption(new ImportOption.DoNotIncludeTests())
            .importPackages("dev.tuink.editor.session");
        List<String> violations = new ArrayList<>();

        for (var imported : production) {
            Class<?> type = Class.forName(imported.getName(), false, getClass().getClassLoader());
            if (!Modifier.isPublic(type.getModifiers()) || type.getName().equals(CORE_BRIDGE)) {
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
    void mutableCoreBridgeIsRestrictedToDocumentInternalsAndTheTuiCompositionAdapter() {
        JavaClasses production = new ClassFileImporter()
            .withImportOption(new ImportOption.DoNotIncludeTests())
            .importPackages("dev.tuink");
        noClasses()
            .that().resideOutsideOfPackage("dev.tuink.editor.session.document..")
            .and().doNotHaveFullyQualifiedName("dev.tuink.tui.TuiDocumentSession")
            .should().dependOnClassesThat()
            .haveNameMatching("dev\\.tuink\\.editor\\.session\\.document\\.CoreSessionBridge(?:\\$.*)?")
            .because("only the composition adapter may attach core views; portable clients use snapshots and edits")
            .check(production);
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
            return raw.getName().startsWith(CORE_PACKAGE) || raw.getName().equals(CORE_BRIDGE)
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
