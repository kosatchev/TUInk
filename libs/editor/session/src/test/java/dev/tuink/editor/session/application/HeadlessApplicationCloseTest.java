package dev.tuink.editor.session.application;

import static org.junit.jupiter.api.Assertions.*;

import dev.tuink.editor.command.DocumentCommand;
import dev.tuink.editor.document.DocumentId;
import dev.tuink.editor.document.PresentationId;
import dev.tuink.editor.session.application.ApplicationCloseWorkflow.Choice;
import dev.tuink.editor.session.application.ApplicationCloseWorkflow.Continuation;
import dev.tuink.editor.session.application.ApplicationCloseWorkflow.Effect;
import dev.tuink.editor.session.application.ApplicationCloseWorkflow.Outcome;
import dev.tuink.editor.session.application.ApplicationCloseWorkflow.Phase;
import dev.tuink.editor.session.application.ApplicationCloseWorkflow.Policy;
import dev.tuink.editor.session.application.ApplicationCloseWorkflow.SaveProgress;
import dev.tuink.editor.session.application.ApplicationCloseWorkflow.State;
import dev.tuink.editor.session.document.DocumentIoCoordinator;
import dev.tuink.editor.session.document.EditorDocumentSession;
import dev.tuink.editor.session.document.PreparedDocumentOpen;
import dev.tuink.editor.storage.DocumentOpenMode;
import dev.tuink.verification.MemoryDocumentStorage;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import org.junit.jupiter.api.Test;

/** A frontend-free host uses the same close decisions and public session save protocol as TUI. */
class HeadlessApplicationCloseTest {
    @Test
    void saveAllExitsOnlyAfterTheMatchingSessionReceiptAndTheFinalDirtyCheck() throws Exception {
        var storage = new MemoryDocumentStorage();
        var document = dirtyDocument(storage);
        var recipient = new PresentationId("headless-close");
        var saving = saveAllRequested(recipient);
        int calls = storage.calls();
        var work = document.io().beginSave();
        var waiting = ApplicationCloseWorkflow.publishing(saving, work, 100L).state();
        assertEquals(calls, storage.calls(), "owner decisions and capture perform no storage I/O");
        assertTrue(waiting.running());
        assertSame(work, waiting.waiting().operation());

        var completion = worker(work::perform);
        assertTrue(document.status().dirty(), "worker publication cannot mark the owner-side session saved");
        assertEquals("draft original", new String(storage.bytes("source.md"), StandardCharsets.UTF_8));
        var transition = document.io().accept(work, completion);
        assertInstanceOf(DocumentIoCoordinator.Saved.class, transition);
        assertFalse(document.status().dirty());
        var received = ApplicationCloseWorkflow.completed(waiting, work, Outcome.SAVED, document.status().dirty());
        assertEquals(Phase.SAVING, received.state().phase(), "the authorized Save-All continuation decides when it is finished");
        assertEquals(Effect.NONE, received.effect());
        var finished = ApplicationCloseWorkflow.saveProgress(received.state(), recipient, SaveProgress.FINISHED);
        assertFalse(finished.running());
        assertEquals(Phase.CLOSED, finished.phase());
        assertSame(recipient, finished.recipient());
    }

    @Test
    void uncertainSaveCancelsCloseAndLeavesTheSessionDraftAvailableForRecovery() throws Exception {
        var storage = new MemoryDocumentStorage();
        var document = dirtyDocument(storage);
        var saving = saveAllRequested(new PresentationId("headless-close"));
        storage.failNextWrite(MemoryDocumentStorage.WriteFailure.AFTER_COMMIT);
        var work = document.io().beginSave();
        var waiting = ApplicationCloseWorkflow.publishing(saving, work, 100L).state();
        var transition = document.io().accept(work, worker(work::perform));
        var failure = assertInstanceOf(DocumentIoCoordinator.Failed.class, transition);
        assertEquals(DocumentIoCoordinator.FailureKind.UNKNOWN, failure.kind());
        var decision = ApplicationCloseWorkflow.completed(waiting, work, Outcome.FAILED, document.status().dirty());
        assertEquals(Phase.OPEN, decision.state().phase());
        assertEquals(Effect.NONE, decision.effect());
        assertTrue(decision.state().running());
        assertTrue(document.status().dirty());
        assertEquals(List.of("draft original"), document.prepareRecovery(document.defaultViewId()).materialize().lines());
        assertEquals("draft original", new String(storage.bytes("source.md"), StandardCharsets.UTF_8),
            "possible publication does not authorize dropping the draft or exiting");
    }

    private static State<PresentationId, DocumentIoCoordinator.Work> saveAllRequested(PresentationId recipient) {
        State<PresentationId, DocumentIoCoordinator.Work> open = State.open();
        var request = ApplicationCloseWorkflow.request(open, recipient, null);
        assertEquals(Effect.CANCEL_PREPARATION, request.effect());
        var ready = ApplicationCloseWorkflow.prepared(request.state(), null, Policy.LIVE_RECOVERY,
            Continuation.RECHECK_DOCUMENTS, 0L, true);
        assertEquals(Effect.CONFIRM, ready.effect());
        var choice = ApplicationCloseWorkflow.choose(ready.state(), Choice.SAVE);
        assertEquals(Effect.SAVE_ALL, choice.effect());
        return choice.state();
    }

    private static EditorDocumentSession dirtyDocument(MemoryDocumentStorage storage) throws Exception {
        storage.put("source.md", "original".getBytes(StandardCharsets.UTF_8));
        var document = EditorDocumentSession.create(new DocumentId("headless-document"), List.of(""));
        var read = new PreparedDocumentOpen(storage.resource("source.md"), DocumentOpenMode.DETECT, false);
        document.openPrepared(read.accept(worker(read::read)));
        document.execute(document.defaultViewId(), new DocumentCommand.Input("draft "));
        assertTrue(document.status().dirty());
        return document;
    }

    private static <T> T worker(Callable<T> operation) throws Exception {
        var task = new FutureTask<>(operation);
        var thread = new Thread(task, "headless-close-storage");
        thread.start();
        thread.join(5_000L);
        assertFalse(thread.isAlive(), "detached storage work must finish");
        return task.get();
    }
}
