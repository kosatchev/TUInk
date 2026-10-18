package dev.tuink.tui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tuiframe.input.KeyStroke;
import dev.tuiframe.input.TextInput;
import dev.tuink.tui.command.TuinkCommand;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SaveWorkflowIntegrationTest {
    @TempDir Path directory;

    @Test
    void newTargetAndOverwriteCompleteTheSameSavePipeline() throws Exception {
        for (boolean overwrite : List.of(false, true)) {
            Path scenario = directory.resolve(Boolean.toString(overwrite));
            try (EditorWindowFixture window = new EditorWindowFixture(scenario, false, List.of("original"))) {
                window.event(new TextInput("draft "));
                var layout = window.active().tabSession().textLayout();
                var builder = window.context.renderPlanBuilder();
                Path target = scenario.resolve("saved.java");
                if (overwrite) {
                    Files.writeString(target, "old target");
                }
                submitFilename(window, target);
                if (overwrite) {
                    assertEquals("old target", Files.readString(target));
                    window.event(new TextInput("y"));
                }
                assertEquals("draft original", Files.readString(target));
                assertEquals(target, window.active().state().getCurrentFile());
                assertFalse(window.active().state().isDirty());
                assertEquals("", window.filename.text());
                assertNotSame(builder, window.context.renderPlanBuilder());
                assertSame(layout, window.context.renderPlanBuilder().textLayout());
                assertTrue(window.notification.message.startsWith("Saved " + target));
                window.event(new TextInput("after "));
                assertEquals(List.of("draft after original"), window.active().tabSession().model().snapshotLines());
            }
        }
    }

    @Test
    void overwriteRefusalReturnsToTheSameDraftAndCancelKeepsTheDocument() throws Exception {
        try (EditorWindowFixture window = new EditorWindowFixture(directory, false, List.of("original"))) {
            window.event(new TextInput("draft "));
            Path target = directory.resolve("existing.txt");
            Files.writeString(target, "untouched");
            submitFilename(window, target);
            chooseDiscard(window);
            assertEquals(target.toString(), window.filename.text());
            window.event(KeyStroke.of(KeyStroke.Key.ESCAPE));
            window.event(new TextInput("after "));
            assertEquals("untouched", Files.readString(target));
            assertEquals(window.document, window.active().state().getCurrentFile());
            assertEquals(List.of("draft after original"), window.active().tabSession().model().snapshotLines());
        }
    }

    @Test
    void quitResumesAfterSaveAsAndOverwriteAndCancellationSavesNothing() throws Exception {
        try (EditorWindowFixture window = new EditorWindowFixture(directory, false, List.of("original"))) {
            window.ui.confirmOnCloseSetting = true;
            window.event(new TextInput("first "));
            var first = window.active();
            window.command(TuinkCommand.TAB_NEW);
            window.event(new TextInput("second"));
            var second = window.active();
            window.command(TuinkCommand.APP_QUIT);
            window.event(new TextInput("y"));
            window.event(KeyStroke.of(KeyStroke.Key.ESCAPE));
            assertTrue(window.controller.running());
            assertTrue(first.state().isDirty());
            assertTrue(second.state().isDirty());
            assertEquals("original", Files.readString(window.document));

            window.command(TuinkCommand.APP_QUIT);
            window.event(new TextInput("y"));
            Path target = directory.resolve("existing.txt");
            Files.writeString(target, "replace me");
            replaceFilename(window, target);
            chooseDiscard(window);
            assertEquals(target.toString(), window.filename.text());
            Path retry = directory.resolve("second.java");
            replaceFilename(window, retry);

            assertFalse(window.controller.running());
            assertEquals("first original", Files.readString(window.document));
            assertEquals("second", Files.readString(retry));
            assertEquals("replace me", Files.readString(target));
            assertFalse(first.state().isDirty());
            assertFalse(second.state().isDirty());
            assertEquals(retry, window.active().state().getCurrentFile());
        }
    }

    @Test
    void closeTabResumesAfterOverwriteAndPreservesTheOtherTab() throws Exception {
        try (EditorWindowFixture window = new EditorWindowFixture(directory, false, List.of("original"))) {
            window.ui.confirmOnCloseSetting = true;
            var first = window.active();
            window.command(TuinkCommand.TAB_NEW);
            window.event(new TextInput("second"));
            window.command(TuinkCommand.TAB_CLOSE);
            window.event(new TextInput("y"));
            Path target = directory.resolve("existing.txt");
            Files.writeString(target, "replace me");
            replaceFilename(window, target);
            window.event(new TextInput("y"));

            assertEquals("second", Files.readString(target));
            assertEquals(1, window.tabs.size());
            assertSame(first, window.active());
            assertTrue(window.controller.running());
            assertEquals("", window.filename.text());
            window.event(new TextInput("after "));
            assertEquals(List.of("after original"), first.tabSession().model().snapshotLines());
        }
    }

    @Test
    void externalSaveConflictKeepsTheDraftAndCanBeRetriedWithSaveAs() throws Exception {
        try (EditorWindowFixture window = new EditorWindowFixture(directory, false, List.of("original"))) {
            window.event(new TextInput("draft "));
            Files.writeString(window.document, "external");
            window.command(TuinkCommand.DOCUMENT_SAVE);
            assertTrue(window.notification.message.startsWith("Save failed:"));
            assertTrue(window.active().state().isDirty());
            assertEquals("external", Files.readString(window.document));
            Path copy = directory.resolve("copy.txt");
            submitFilename(window, copy);
            assertEquals("draft original", Files.readString(copy));
            assertEquals("external", Files.readString(window.document));
            assertFalse(window.active().state().isDirty());
        }
    }

    private static void chooseDiscard(EditorWindowFixture window) {
        window.event(KeyStroke.of(KeyStroke.Key.HOME));
        window.event(KeyStroke.of(KeyStroke.Key.ARROW_RIGHT));
        window.event(KeyStroke.of(KeyStroke.Key.ENTER));
    }

    private static void replaceFilename(EditorWindowFixture window, Path target) {
        window.event(KeyStroke.of(KeyStroke.Key.A, KeyStroke.Modifier.CTRL));
        window.event(new TextInput(target.toString()));
        window.event(KeyStroke.of(KeyStroke.Key.ENTER));
    }

    static void submitFilename(EditorWindowFixture window, Path target) {
        window.command(TuinkCommand.DOCUMENT_SAVE_AS);
        window.event(KeyStroke.of(KeyStroke.Key.A, KeyStroke.Modifier.CTRL));
        window.event(new TextInput(target.toString()));
        window.event(KeyStroke.of(KeyStroke.Key.ENTER));
    }
}
