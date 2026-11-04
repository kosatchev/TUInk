package dev.tuink.app;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Complementary gates: interaction/search cannot recreate the Markdown preparation pipeline or editing owner. */
class SharedPreviewPreparationArchitectureTest {
    private static final String SERVICE = "dev.tuink.tui.MarkdownPreviewService";
    private static final JavaClasses PRODUCTION = new ClassFileImporter()
        .withImportOption(new ImportOption.DoNotIncludeTests())
        .importPackages("dev.tuink", "dev.tuiframe");

    private static final String LAYERS = "dev.tuink.tui.FrameComposition$Layers";
    private static final String CHROME = "dev.tuink.tui.WindowChrome";
    private static final String WINDOW = "dev.tuink.tui.EditorWindowController";
    private static final String HOST = "dev.tuink.tui.TerminalEditorApp";
    private static final String RECT = "dev.tuiframe.geometry.Rect";

    @Test void preparedLayersAreTheOnlyModalGeometryProducerAndValidateBothFrameKinds() {
        var layers = PRODUCTION.get(LAYERS);
        var bounds = layers.getFields().stream().filter(field -> field.getName().equals("blockingBounds")).findFirst().orElseThrow();
        assertEquals(RECT, bounds.getRawType().getName());
        assertTrue(bounds.getModifiers().contains(JavaModifier.FINAL), "Prepared geometry is immutable");
        List<String> violations = new ArrayList<>();
        boolean chromeBuilds = false;
        for (var type : PRODUCTION) {
            for (var call : type.getConstructorCallsFromSelf()) {
                if (!call.getTargetOwner().getName().equals(LAYERS)) continue;
                if (type.getName().equals(CHROME)) chromeBuilds = true;
                else violations.add("Layers constructed outside WindowChrome: " + call.getDescription());
            }
            for (var call : type.getMethodCallsFromSelf()) {
                if (!call.getTargetOwner().getName().equals(LAYERS)
                    || !call.getTarget().getName().equals("prepareBlockingBounds")) continue;
                boolean preparation = type.getName().equals(CHROME);
                boolean validation = type.getName().equals(LAYERS) && call.getOrigin().getName().equals("validateGeometry");
                if (!preparation && !validation)
                    violations.add("Modal geometry recalculated outside preparation/validation: " + call.getDescription());
            }
        }
        assertTrue(chromeBuilds, "The producer must actually build ready Layers");
        assertTrue(violations.isEmpty(), () -> String.join("\n", violations));
        for (String frame : List.of("dev.tuink.tui.FrameComposition", "dev.tuink.tui.PreviewFrameComposition"))
            assertTrue(PRODUCTION.get(frame).getMethodCallsFromSelf().stream().anyMatch(call ->
                    call.getTargetOwner().getName().equals(LAYERS) && call.getTarget().getName().equals("validateGeometry")),
                () -> frame + " must validate its actual prepared modal bounds");
    }

    @Test void windowCannotCacheOrRecalculateItsOwnModalRectangle() {
        var window = PRODUCTION.get(WINDOW);
        for (var field : window.getFields()) {
            assertFalse(field.getName().equals("modalInputBounds"), "The superseded geometry cache cannot return");
            if (field.getRawType().getName().equals(RECT)) {
                assertEquals("NO_BLOCKING_BOUNDS", field.getName(), "Only the startup empty rectangle may be stored outside Layers");
                assertTrue(field.getModifiers().containsAll(java.util.Set.of(JavaModifier.STATIC, JavaModifier.FINAL)));
            }
        }
        assertEquals(1, window.getFields().stream().filter(field -> field.getRawType().getName().equals(LAYERS)).count(),
            "The controller publishes one completed window Layers value");
        var getters = window.getMethods().stream().filter(method -> method.getName().equals("blockingBounds")).toList();
        assertEquals(1, getters.size());
        assertTrue(getters.get(0).getRawParameterTypes().isEmpty());
        assertEquals(RECT, getters.get(0).getRawReturnType().getName());
        var calls = window.getMethodCallsFromSelf().stream().filter(call -> call.getOrigin().getName().equals("blockingBounds")).toList();
        assertEquals(1, calls.size(), "The getter may only read the already prepared Layers bounds");
        assertEquals(LAYERS, calls.get(0).getTargetOwner().getName());
        assertEquals("blockingBounds", calls.get(0).getTarget().getName());
        assertFalse(window.getConstructorCallsFromSelf().stream().anyMatch(call -> call.getOrigin().getName().equals("blockingBounds")),
            "The input getter must not construct replacement geometry");
        assertFalse(window.getMethods().stream().anyMatch(method -> method.getRawReturnType().getName().equals(RECT)
                && !method.getName().equals("blockingBounds") && (method.getName().toLowerCase(java.util.Locale.ROOT).contains("blocking")
                    || method.getName().toLowerCase(java.util.Locale.ROOT).contains("modal"))),
            "No renamed modal geometry calculation may coexist with the prepared-bounds getter");
    }

    @Test void cursorAndAttentionReadPreparedLayersAndHostGeometryAliasesCannotReturn() {
        var host = PRODUCTION.get(HOST);
        assertFalse(host.getMethods().stream().anyMatch(method -> method.getName().equals("blockingSurface")),
            "The removed host geometry entry points must not return, even as compatibility overloads");
        for (String consumer : List.of("frameCursor", "paintBlockingAttention")) {
            assertTrue(host.getMethodCallsFromSelf().stream().anyMatch(call -> call.getOrigin().getName().equals(consumer)
                    && call.getTargetOwner().getName().equals(LAYERS) && call.getTarget().getName().equals("blockingBounds")),
                () -> consumer + " must read the same ready Layers geometry used by input");
            assertFalse(host.getConstructorCallsFromSelf().stream().anyMatch(call -> call.getOrigin().getName().equals(consumer)
                    && call.getTargetOwner().getName().equals(RECT)),
                () -> consumer + " cannot recalculate modal rectangles");
        }
    }

    @Test void previewGeometryAndSearchDoNotAcquireMutableEditingOwners() {
        for (String type : List.of("dev.tuink.render.markdown.MarkdownTextSurface",
                "dev.tuink.render.markdown.MarkdownGeometry", "dev.tuink.tui.MarkdownPreviewState"))
            assertNotNull(PRODUCTION.get(type));
        noClasses().that().haveNameMatching(
                "dev\\.tuink\\.(?:render\\.markdown\\.(?:MarkdownTextSurface|MarkdownGeometry)"
                + "|tui\\.(?:PreviewSelection|MarkdownPreviewState|PreviewPresentationView|MarkdownPreviewPainter"
                + "|PreviewSearch[^.$]*|MarkdownPreviewSearch[^.$]*))(?:\\$.*)?")
            .should().dependOnClassesThat().haveNameMatching(
                "dev\\.tuink\\.(?:core\\.(?:EditorEngine|EditorState|TextModel|CoreDocumentBackend)"
                + "|editor\\.session\\.document\\.EditorDocumentSession|tui\\.TuiDocumentSession)(?:\\$.*)?")
            .because("search and visual projection stay usable after closing Source; frozen save subjects remain allowed")
            .check(PRODUCTION);
    }

    @Test void previewParserLayoutAndCodePreparationStayInsideTheExistingWorkerBoundary() {
        assertNotNull(PRODUCTION.get(SERVICE));
        List<String> violations = new ArrayList<>();
        for (var type : PRODUCTION) {
            String origin = type.getName();
            if (!origin.startsWith("dev.tuink.tui.") || origin.equals(SERVICE) || origin.startsWith(SERVICE + "$")) continue;
            for (var call : type.getMethodCallsFromSelf()) {
                String owner = call.getTargetOwner().getName();
                String method = call.getTarget().getName();
                boolean parse = method.equals("parse") && (owner.equals("dev.tuink.markdown.MarkdownParser")
                    || owner.equals("dev.tuink.markdown.commonmark.CommonmarkMarkdownParser"));
                boolean layout = method.equals("layout") && owner.equals("dev.tuink.render.markdown.MarkdownLayout");
                if (parse || layout) violations.add(call.getDescription());
            }
            for (var call : type.getConstructorCallsFromSelf()) {
                String owner = call.getTargetOwner().getName();
                boolean layout = owner.equals("dev.tuink.render.markdown.MarkdownLayout");
                // The highlighter's convenience constructor delegates to its own bounded constructor.
                boolean highlighter = owner.equals("dev.tuink.tui.MarkdownCodeHighlighter") && !origin.equals(owner);
                if (layout || highlighter) violations.add(call.getDescription());
            }
        }
        assertTrue(violations.isEmpty(), () -> "Preparation escaped its bounded worker:\n" + String.join("\n", violations));
    }
}
