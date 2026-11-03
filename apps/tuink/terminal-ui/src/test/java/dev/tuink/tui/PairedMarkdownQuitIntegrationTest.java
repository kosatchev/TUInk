package dev.tuink.tui;

import static org.junit.jupiter.api.Assertions.*;

import dev.tuiframe.input.TextInput;
import dev.tuink.storage.jvm.BlockingPublicationStorage;
import dev.tuink.tui.command.TuinkCommand;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Exercises deferred snapshot quit through the real window dispatcher and owner turn. */
class PairedMarkdownQuitIntegrationTest {
    @TempDir Path directory;

    @Test void quittingFromAnUnrelatedSourceWaitsForTheFrozenPublicationThenExitsAfterItsReceipt() throws Exception {
        quitDuringFrozenPublication(false);
    }

    @Test void anUnknownFrozenPublicationKeepsTheWindowAndSnapshotOpenAfterQuittingFromAnotherSource() throws Exception {
        quitDuringFrozenPublication(true);
    }

    @Test void aTimedOutFrozenQuitKeepsTheWindowAndALateSuccessCannotReviveIt() throws Exception {
        quitDuringFrozenPublication(false, true);
    }

    private void quitDuringFrozenPublication(boolean unknownOutcome) throws Exception {
        quitDuringFrozenPublication(unknownOutcome, false);
    }

    private void quitDuringFrozenPublication(boolean unknownOutcome, boolean timeout) throws Exception {
        var tasks = new ArrayDeque<Runnable>();
        var storage = new BlockingPublicationStorage(unknownOutcome);
        try (var window = new EditorWindowFixture(directory, false, List.of("original"), tasks::addLast)) {
            window.ui.confirmOnCloseSetting = true;
            // Retain native file identity while injecting only the existing provider publication seam.
            window.active().tabSession().engine().open(storage.resource(window.document));
            window.frame();
            window.event(new TextInput("draft "));
            PreviewTab frozen = PairedMarkdownSaveIntegrationTest.freezeDirtySource(window);
            var captured = PairedMarkdownSaveIntegrationTest.closed(frozen).source();
            var frozenId = frozen.presentationId();
            var closedView = captured.text().documentId();
            assertTrue(window.tabs.isEmpty());

            window.command(TuinkCommand.TAB_NEW);
            var otherSource = window.active();
            var otherId = otherSource.presentationId();
            assertFalse(otherSource.state().isDirty());
            window.command(TuinkCommand.TAB_PREVIOUS);
            assertEquals(frozenId, window.selected().presentationId());
            window.command(TuinkCommand.DOCUMENT_SAVE);
            assertTrue(window.documentIo.busy());
            assertEquals(1, tasks.size());

            Thread writer = new Thread(tasks.removeFirst(), "test-frozen-publication");
            writer.start();
            try {
                assertTrue(storage.awaitPublication(), "the writer must cross its publication fence before quit");
                assertEquals("original", Files.readString(window.document));
                window.command(TuinkCommand.TAB_NEXT);
                assertEquals(otherId, window.selected().presentationId());
                window.command(TuinkCommand.APP_QUIT);
                assertTrue(window.controller.running(), "quit cannot discard a publishing frozen snapshot");
                assertTrue(window.documentIo.busy());
                assertTrue(window.selected().uiState().modalState.isNone());
                assertEquals(otherId, window.selected().presentationId());
                assertSame(captured, PairedMarkdownSaveIntegrationTest.closed(
                    (PreviewTab) window.context.presentation(frozenId)).source());
                if (timeout) {
                    var operation = window.documentIo.pendingOperation();
                    window.controller.checkWindowCloseDeadline(System.nanoTime() + WindowCloseWorkflow.WAIT_NANOS);
                    assertTrue(window.controller.running());
                    assertTrue(otherSource.uiState().notification.message.contains("outcome is still pending"));
                    window.command(TuinkCommand.APP_QUIT);
                    window.command(TuinkCommand.APP_QUIT);
                    window.controller.checkWindowCloseDeadline(System.nanoTime() + 2 * WindowCloseWorkflow.WAIT_NANOS);
                    assertSame(operation, window.documentIo.pendingOperation());
                    assertTrue(window.controller.running());
                    assertTrue(window.selected().uiState().modalState.isNone());
                }
            } finally {
                storage.release();
                writer.join(5_000L);
                assertFalse(writer.isAlive(), "the released publication must terminate");
            }

            assertEquals("draft original", Files.readString(window.document));
            assertTrue(window.documentIo.busy(), "the worker cannot complete the owner-side quit continuation");
            assertTrue(window.controller.running());
            window.frame();
            assertFalse(window.documentIo.busy());
            assertEquals(otherId, window.selected().presentationId(), "a receipt must not activate the owning preview");
            assertSame(otherSource.documentSession(), window.active().documentSession());
            assertEquals(1, window.tabs.size(), "no hidden editing session may be recreated for the snapshot");
            assertNotEquals(closedView, window.active().documentSession().document().documentId());
            var retained = assertInstanceOf(PreviewTab.class, window.context.presentation(frozenId));
            var retainedSource = PairedMarkdownSaveIntegrationTest.closed(retained).source();
            assertSame(captured.text(), retainedSource.text());
            assertEquals(List.of("draft original"), retainedSource.text().lines());
            assertTrue(retained.uiState().modalState.isNone());
            if (unknownOutcome || timeout) {
                assertTrue(window.controller.running(), "an uncertain publication cancels deferred quit");
                if (unknownOutcome) {
                    assertSame(captured, retainedSource, "unconfirmed receipts cannot advance the snapshot binding or baseline");
                    assertTrue(retained.uiState().notification.requiresAttention());
                    assertEquals(frozen.generation(), retained.generation());
                } else {
                    assertEquals(frozen.generation() + 1, retained.generation());
                    assertTrue(retained.uiState().notification.message.startsWith("Saved "));
                }
                assertFalse(otherSource.state().isDirty());
            } else {
                assertFalse(window.controller.running(), "the confirmed receipt may finish the requested exit");
                assertEquals(frozen.generation() + 1, retained.generation());
                assertTrue(retained.uiState().notification.message.startsWith("Saved "));
            }
        }
    }
}
