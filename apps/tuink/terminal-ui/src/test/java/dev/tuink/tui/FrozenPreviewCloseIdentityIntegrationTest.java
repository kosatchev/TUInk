package dev.tuink.tui;

import static org.junit.jupiter.api.Assertions.*;

import dev.tuiframe.input.KeyStroke;
import dev.tuiframe.input.TextInput;
import dev.tuink.editor.session.document.FrozenDocumentSession;
import dev.tuink.storage.jvm.JvmDocumentStorage;
import dev.tuink.tui.command.TuinkCommand;
import dev.tuink.tui.flow.ConfirmChoice;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.List;
import java.util.concurrent.Executor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** Local close questions and accepted background receipts must keep their original preview identity. */
class FrozenPreviewCloseIdentityIntegrationTest {
    private static final List<String> FIRST = List.of("# First", "", "first raw text  ");
    private static final List<String> SECOND = List.of("# Second", "", "second raw text  ");
    @TempDir Path directory;

    @ParameterizedTest
    @EnumSource(ConfirmChoice.class)
    void answeringAnEarlierCloseQuestionDoesNotAnswerTheLaterQuestionInAnotherPreview(ConfirmChoice choice) throws Exception {
        try (var window = new EditorWindowFixture(directory, "first.md", false, FIRST)) {
            var pair = deletedPreviews(window);
            armBothCloseQuestions(window, pair);
            switch (choice) {
                case CANCEL -> window.event(KeyStroke.of(KeyStroke.Key.ESCAPE));
                case DISCARD -> window.event(new TextInput("n"));
                case SAVE -> {
                    window.event(ctrl(KeyStroke.Key.S));
                    assertEquals(pair.first().presentationId(), window.selected().presentationId());
                    assertTrue(pair.first().uiState().modalState.isSaveAs());
                    Path target = directory.resolve("saved-first.md");
                    submitFilename(window, target);
                    window.await(() -> !window.documentIo.busy()
                        && window.context.presentation(pair.first().presentationId()) == null);
                    assertEquals(String.join("\n", FIRST), Files.readString(target));
                    assertFalse(window.host.deletedNotices.hasUnsavedRemoval(pair.firstSubject().documentId()));
                }
            }

            var retainedSecond = assertInstanceOf(PreviewTab.class,
                window.context.presentation(pair.second().presentationId()));
            assertSame(pair.secondSubject(), closed(retainedSecond));
            assertTrue(retainedSecond.uiState().modalState.isCloseTabConfirm(),
                "Answering the first preview must preserve the second preview's own question");
            assertNull(pair.secondSubject().binding().resource());
            assertEquals(SECOND, pair.secondSubject().source().text().lines());
            assertTrue(window.host.deletedNotices.hasUnsavedRemoval(pair.secondSubject().documentId()));
            assertTrue(window.tabs.isEmpty());
            assertTrue(window.controller.running());

            if (choice == ConfirmChoice.CANCEL) {
                var retainedFirst = assertInstanceOf(PreviewTab.class,
                    window.context.presentation(pair.first().presentationId()));
                assertSame(pair.firstSubject(), closed(retainedFirst));
                assertTrue(retainedFirst.uiState().modalState.isNone());
                assertEquals(pair.first().presentationId(), window.selected().presentationId());
                assertTrue(window.host.deletedNotices.hasUnsavedRemoval(pair.firstSubject().documentId()));

                // Cancel first must not remove the second preview's save-and-close continuation.
                window.command(TuinkCommand.TAB_NEXT);
                assertEquals(pair.second().presentationId(), window.selected().presentationId());
                window.event(ctrl(KeyStroke.Key.S));
                assertTrue(retainedSecond.uiState().modalState.isSaveAs());
                Path target = directory.resolve("saved-second.md");
                submitFilename(window, target);
                window.await(() -> !window.documentIo.busy()
                    && window.context.presentation(pair.second().presentationId()) == null);
                assertEquals(String.join("\n", SECOND), Files.readString(target));
                assertSame(pair.firstSubject(), closed((PreviewTab) window.selected()));
                assertNull(pair.firstSubject().binding().resource());
                assertTrue(window.host.deletedNotices.hasUnsavedRemoval(pair.firstSubject().documentId()));
            } else {
                assertNull(window.context.presentation(pair.first().presentationId()));
                assertEquals(pair.second().presentationId(), window.selected().presentationId());
                window.event(KeyStroke.of(KeyStroke.Key.ESCAPE));
                assertSame(pair.secondSubject(), closed((PreviewTab) window.selected()));
                assertTrue(window.selected().uiState().modalState.isNone());
                assertTrue(window.host.deletedNotices.hasUnsavedRemoval(pair.secondSubject().documentId()));
            }
            assertFalse(Files.exists(window.document));
            assertFalse(Files.exists(directory.resolve("second.md")));
            assertTrue(window.tabs.isEmpty(), "No close answer may manufacture an editing source");
        }
    }

    @Test
    void anAcceptedBackgroundSaveClosesItsOwnPreviewAndKeepsTheSelectedPreviewsCloseQuestion() throws Exception {
        var executor = new ControlledExecutor();
        try (var window = new EditorWindowFixture(directory, "first.md", false, FIRST, executor)) {
            var pair = deletedPreviews(window);
            assertEquals(pair.first().presentationId(), window.selected().presentationId());
            window.command(TuinkCommand.TAB_CLOSE);
            window.event(ctrl(KeyStroke.Key.S));
            assertTrue(pair.first().uiState().modalState.isSaveAs());

            executor.delayed = true;
            Path target = directory.resolve("background-first.md");
            submitFilename(window, target);
            assertTrue(window.documentIo.busy());
            assertEquals(1, executor.tasks.size());
            window.command(TuinkCommand.TAB_NEXT);
            assertEquals(pair.second().presentationId(), window.selected().presentationId());
            window.command(TuinkCommand.TAB_CLOSE);
            assertTrue(pair.second().uiState().modalState.isCloseTabConfirm());
            assertFalse(Files.exists(target));

            executor.runNext();
            window.frame(); // Accept inspection while the other preview owns the visible question.
            assertEquals(1, executor.tasks.size());
            assertEquals(pair.second().presentationId(), window.selected().presentationId());
            assertTrue(pair.second().uiState().modalState.isCloseTabConfirm());
            executor.runNext();
            assertEquals(String.join("\n", FIRST), Files.readString(target));
            assertNotNull(window.context.presentation(pair.first().presentationId()),
                "The storage worker must not retire a UI presentation before owner-side receipt acceptance");
            window.frame();

            assertFalse(window.documentIo.busy());
            assertNull(window.context.presentation(pair.first().presentationId()));
            assertEquals(pair.second().presentationId(), window.selected().presentationId());
            assertSame(pair.secondSubject(), closed((PreviewTab) window.selected()));
            assertTrue(pair.second().uiState().modalState.isCloseTabConfirm());
            assertEquals(SECOND, pair.secondSubject().source().text().lines());
            assertNull(pair.secondSubject().binding().resource());
            assertTrue(window.host.deletedNotices.hasUnsavedRemoval(pair.secondSubject().documentId()));
            assertFalse(window.host.deletedNotices.hasUnsavedRemoval(pair.firstSubject().documentId()));
            assertTrue(window.tabs.isEmpty());

            window.event(KeyStroke.of(KeyStroke.Key.ESCAPE));
            assertTrue(window.selected().uiState().modalState.isNone());
            assertSame(pair.secondSubject(), closed((PreviewTab) window.selected()));
            assertTrue(window.controller.running());
        }
    }

    private record Pair(PreviewTab first, FrozenDocumentSession firstSubject,
        PreviewTab second, FrozenDocumentSession secondSubject) {}

    private Pair deletedPreviews(EditorWindowFixture window) throws Exception {
        window.controller.openFolder(directory);
        window.await(() -> window.ui.sidebar.tree != null && !window.ui.sidebar.tree.loading());
        PreviewTab first = freezeSelectedSource(window);
        Path secondPath = Files.writeString(directory.resolve("second.md"), String.join("\n", SECOND));
        var secondSource = window.host.newEditorTab();
        secondSource.tabSession().engine().open(JvmDocumentStorage.resource(secondPath));
        window.context.addNewTabAndActivate(secondSource);
        window.frame();
        PreviewTab second = freezeSelectedSource(window);
        var pair = new Pair(first, closed(first), second, closed(second));
        assertNotEquals(pair.firstSubject().documentId(), pair.secondSubject().documentId());
        assertTrue(window.tabs.isEmpty());

        Files.delete(window.document);
        Files.delete(secondPath);
        window.command(TuinkCommand.EXPLORER_REFRESH);
        window.await(() -> window.host.deletedNotices.hasUnsavedRemoval(pair.secondSubject().documentId()));
        window.command(TuinkCommand.TAB_PREVIOUS);
        assertEquals(first.presentationId(), window.selected().presentationId());
        window.command(TuinkCommand.EXPLORER_REFRESH);
        window.await(() -> window.host.deletedNotices.hasUnsavedRemoval(pair.firstSubject().documentId()));
        assertNull(pair.firstSubject().binding().resource());
        assertNull(pair.secondSubject().binding().resource());
        return pair;
    }

    private static PreviewTab freezeSelectedSource(EditorWindowFixture window) throws Exception {
        window.command(TuinkCommand.VIEW_TOGGLE_MARKDOWN_PREVIEW);
        window.await(() -> window.selected() instanceof PreviewTab && window.preview().ready());
        window.command(TuinkCommand.TAB_PREVIOUS);
        assertInstanceOf(TerminalEditorApp.EditorTab.class, window.selected());
        window.command(TuinkCommand.TAB_CLOSE);
        window.await(() -> window.selected() instanceof PreviewTab preview
            && preview.source() instanceof PreviewTab.ClosedSnapshot && window.preview().ready());
        return (PreviewTab) window.selected();
    }

    private static void armBothCloseQuestions(EditorWindowFixture window, Pair pair) {
        assertEquals(pair.first().presentationId(), window.selected().presentationId());
        window.command(TuinkCommand.TAB_CLOSE);
        assertTrue(pair.first().uiState().modalState.isCloseTabConfirm());
        window.command(TuinkCommand.TAB_NEXT);
        assertEquals(pair.second().presentationId(), window.selected().presentationId());
        window.command(TuinkCommand.TAB_CLOSE);
        assertTrue(pair.second().uiState().modalState.isCloseTabConfirm());
        assertTrue(pair.first().uiState().modalState.isCloseTabConfirm());
        window.command(TuinkCommand.TAB_PREVIOUS);
        assertEquals(pair.first().presentationId(), window.selected().presentationId());
    }

    private static FrozenDocumentSession closed(PreviewTab preview) {
        return assertInstanceOf(PreviewTab.ClosedSnapshot.class, preview.source()).subject();
    }

    private static void submitFilename(EditorWindowFixture window, Path target) {
        assertTrue(window.selected().uiState().modalState.isSaveAs());
        window.event(ctrl(KeyStroke.Key.A));
        window.event(new TextInput(target.toString()));
        assertEquals(target.toString(), window.filename.text());
        window.event(KeyStroke.of(KeyStroke.Key.ENTER));
    }

    private static KeyStroke ctrl(KeyStroke.Key key) { return KeyStroke.of(key, KeyStroke.Modifier.CTRL); }

    private static final class ControlledExecutor implements Executor {
        final ArrayDeque<Runnable> tasks = new ArrayDeque<>();
        boolean delayed;
        @Override public void execute(Runnable task) {
            if (delayed) tasks.addLast(task); else task.run();
        }
        void runNext() {
            assertFalse(tasks.isEmpty(), "A detached document operation must be queued");
            tasks.removeFirst().run();
        }
    }
}
