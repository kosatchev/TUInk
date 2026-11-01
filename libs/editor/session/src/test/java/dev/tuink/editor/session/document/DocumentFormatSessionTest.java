package dev.tuink.editor.session.document;

import static org.junit.jupiter.api.Assertions.*;

import dev.tuink.editor.command.DocumentCommand;
import dev.tuink.editor.command.DocumentCommandResult;
import dev.tuink.editor.document.DocumentId;
import dev.tuink.editor.document.TextPosition;
import dev.tuink.editor.format.DocumentEncoding;
import dev.tuink.editor.format.DocumentFormatSpec;
import dev.tuink.editor.format.DocumentFormatSummary;
import dev.tuink.editor.format.LineEndingPolicy;
import dev.tuink.editor.storage.DocumentEncodingException;
import dev.tuink.editor.storage.DocumentOpenMode;
import dev.tuink.editor.storage.SaveDisposition;
import dev.tuink.verification.MemoryDocumentStorage;
import java.io.InterruptedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class DocumentFormatSessionTest {
    @ParameterizedTest
    @EnumSource(DocumentEncoding.class)
    void everyPresetAndEolPolicyPreservesTextAndProducesExactBytes(DocumentEncoding encoding) throws Exception {
        for (LineEndingPolicy policy : LineEndingPolicy.values()) {
            var storage = new MemoryDocumentStorage();
            var session = loaded(storage, "Привет\r\nмир\rконец\n");
            var original = session.snapshot();
            var originalMetadata = session.status().metadata();
            int calls = storage.calls();
            var prepared = session.prepareFormatChange(session.defaultViewId(), new DocumentFormatSpec(encoding, policy));
            var completion = worker(prepared::perform);
            assertEquals(calls, storage.calls(), "Format validation must not access a resource");
            var applied = assertInstanceOf(DocumentCommandResult.Applied.class,
                session.completeFormatChange(prepared, completion));
            boolean changed = encoding != DocumentEncoding.UTF8 || policy != LineEndingPolicy.KEEP;
            assertEquals(changed, applied.performed());
            assertFalse(applied.textChanged());
            assertFalse(applied.viewChanged());
            assertEquals(original, session.snapshot());
            assertEquals(changed, session.status().dirty());
            assertEquals(encoding, session.formatSummary().encoding());
            assertTrue(session.formatSummary().finalNewline());
            assertEquals(policy == LineEndingPolicy.KEEP ? DocumentFormatSummary.LineEnding.MIXED
                : DocumentFormatSummary.LineEnding.valueOf(policy.name()), session.formatSummary().lineEnding());
            assertSame(session.formatSummary(), session.formatSummary(), "Repeated frames reuse the summary");
            var formatMetadata = session.status().metadata();
            if (changed) {
                assertTrue(session.undo());
                assertEquals(originalMetadata, session.status().metadata());
                assertFalse(session.status().dirty());
                assertTrue(session.redo());
                assertEquals(formatMetadata, session.status().metadata());
            } else assertFalse(session.undo());
            var save = session.io().beginSave();
            assertInstanceOf(DocumentIoCoordinator.Saved.class, session.io().accept(save, worker(save::perform)));
            String text = policy == LineEndingPolicy.KEEP ? "Привет\r\nмир\rконец\n"
                : String.join(policy.separator(), original.lines());
            byte[] content = text.getBytes(encoding.charset());
            byte[] bom = encoding.bomKind().bytes();
            byte[] expected = new byte[bom.length + content.length];
            System.arraycopy(bom, 0, expected, 0, bom.length);
            System.arraycopy(content, 0, expected, bom.length, content.length);
            assertArrayEquals(expected, storage.bytes("source"));
            assertFalse(session.status().dirty());
            if (changed) {
                assertTrue(session.undo());
                assertTrue(session.status().dirty());
                assertEquals(originalMetadata, session.status().metadata());
                assertTrue(session.redo());
                assertFalse(session.status().dirty());
            }
        }
    }

    @Test
    void formatUndoRestoresMixedLayoutAcrossOrdinaryMultilineTextHistory() throws Exception {
        var session = loaded(new MemoryDocumentStorage(), "one\r\ntwo\rthree\n");
        var original = session.status().metadata();
        input(session, "A\nB");
        var edited = session.snapshot().lines();
        var editedMetadata = session.status().metadata();
        apply(session, DocumentEncoding.UTF16LE_BOM, LineEndingPolicy.LF);
        var formatted = session.status().metadata();
        input(session, "C\nD");
        var finalText = session.snapshot().lines();
        var finalMetadata = session.status().metadata();
        assertTrue(session.undo());
        assertEquals(edited, session.snapshot().lines());
        assertEquals(formatted, session.status().metadata());
        assertTrue(session.undo());
        assertEquals(editedMetadata, session.status().metadata());
        assertTrue(session.undo());
        assertEquals(List.of("one", "two", "three", ""), session.snapshot().lines());
        assertEquals(original, session.status().metadata());
        assertFalse(session.status().dirty());
        assertTrue(session.redo());
        assertTrue(session.redo());
        assertTrue(session.redo());
        assertEquals(finalText, session.snapshot().lines());
        assertEquals(finalMetadata, session.status().metadata());
    }

    @Test
    void unrepresentableCharacterLeavesMetadataTextHistoryAndRecoveryUnchanged() throws Exception {
        var session = loaded(new MemoryDocumentStorage(), "Привет 😀\r\n");
        var text = session.snapshot();
        var status = session.status();
        for (var encoding : List.of(DocumentEncoding.WINDOWS_1251, DocumentEncoding.CP866, DocumentEncoding.KOI8_R)) {
            var prepared = session.prepareFormatChange(session.defaultViewId(), new DocumentFormatSpec(encoding, LineEndingPolicy.CR));
            var error = assertThrows(DocumentEncodingException.class, () -> worker(prepared::perform));
            assertEquals(0x1F600, error.codePoint());
            assertEquals(encoding.charset().name(), error.charsetName());
            assertEquals(text, session.snapshot());
            assertEquals(status, session.status());
            assertFalse(session.undo());
        }
    }

    @Test
    void noOpDoesNotDirtyInvalidateRedoOrCreateAHistoryEntry() throws Exception {
        var session = loaded(new MemoryDocumentStorage(), "one\ntwo");
        input(session, "X");
        assertTrue(session.undo());
        var before = session.status();
        var prepared = session.prepareFormatChange(session.defaultViewId(), new DocumentFormatSpec(DocumentEncoding.UTF8, LineEndingPolicy.KEEP));
        var applied = assertInstanceOf(DocumentCommandResult.Applied.class,
            session.completeFormatChange(prepared, worker(prepared::perform)));
        assertFalse(applied.performed());
        assertEquals(before, session.status());
        assertTrue(session.redo());
        assertEquals("Xone", session.snapshot().line(0));
    }

    @Test
    void cursorMovementIsAllowedButTextStateRetiredViewAndSaveFencesReject() throws Exception {
        var session = loaded(new MemoryDocumentStorage(), "one\r\ntwo");
        var second = session.attachView();
        var prepared = session.prepareFormatChange(second, new DocumentFormatSpec(DocumentEncoding.UTF8_BOM, LineEndingPolicy.LF));
        var completed = worker(prepared::perform);
        session.execute(second, new DocumentCommand.MoveTo(new TextPosition(1, 2), true));
        var view = session.view(second);
        assertInstanceOf(DocumentCommandResult.Applied.class, session.completeFormatChange(prepared, completed));
        assertEquals(view.cursor(), session.view(second).cursor(), "Format keeps the cursor");
        assertEquals(view.selectionAnchor(), session.view(second).selectionAnchor(), "Format keeps the selection");
        assertEquals(DocumentEncoding.UTF8_BOM, session.formatSummary().encoding());

        var staleText = session.prepareFormatChange(second, new DocumentFormatSpec(DocumentEncoding.UTF8, LineEndingPolicy.CR));
        var staleTextDone = worker(staleText::perform);
        input(session, "X");
        assertRejected(session.completeFormatChange(staleText, staleTextDone), DocumentCommandResult.RejectionReason.STALE_DOCUMENT);

        var staleState = session.prepareFormatChange(second, new DocumentFormatSpec(DocumentEncoding.UTF8, LineEndingPolicy.CR));
        var staleStateDone = worker(staleState::perform);
        apply(session, DocumentEncoding.UTF16BE_BOM, LineEndingPolicy.KEEP);
        assertRejected(session.completeFormatChange(staleState, staleStateDone), DocumentCommandResult.RejectionReason.STALE_DOCUMENT_STATE);

        var retired = session.prepareFormatChange(second, new DocumentFormatSpec(DocumentEncoding.UTF8, LineEndingPolicy.CR));
        var retiredDone = worker(retired::perform);
        session.detachView(second);
        assertRejected(session.completeFormatChange(retired, retiredDone), DocumentCommandResult.RejectionReason.UNKNOWN_VIEW);

        var busy = session.prepareFormatChange(session.defaultViewId(), new DocumentFormatSpec(DocumentEncoding.UTF8, LineEndingPolicy.CR));
        var busyDone = worker(busy::perform);
        var save = session.io().beginSave();
        assertThrows(IllegalStateException.class, () -> session.prepareFormatChange(session.defaultViewId(), busy.spec()));
        assertRejected(session.completeFormatChange(busy, busyDone), DocumentCommandResult.RejectionReason.DOCUMENT_BUSY);
        assertInstanceOf(DocumentIoCoordinator.Saved.class, session.io().accept(save, worker(save::perform)));
    }

    @Test
    void cancelledForeignReusedAndWrongOwnerCompletionsCannotPublish() throws Exception {
        var first = loaded(new MemoryDocumentStorage(), "text");
        var second = loaded(new MemoryDocumentStorage(), "other");
        var spec = new DocumentFormatSpec(DocumentEncoding.UTF8_BOM, LineEndingPolicy.CR);
        var beforeRead = first.prepareFormatChange(first.defaultViewId(), spec);
        assertTrue(beforeRead.cancel());
        assertThrows(InterruptedIOException.class, () -> worker(beforeRead::perform));
        var a = first.prepareFormatChange(first.defaultViewId(), spec);
        var b = second.prepareFormatChange(second.defaultViewId(), spec);
        var ac = worker(a::perform);
        var bc = worker(b::perform);
        assertThrows(IllegalArgumentException.class, () -> second.completeFormatChange(a, ac));
        assertThrows(IllegalArgumentException.class, () -> first.completeFormatChange(a, bc));
        assertRejected(worker(() -> first.completeFormatChange(a, ac)), DocumentCommandResult.RejectionReason.WRONG_OWNER_THREAD);
        assertThrows(IllegalStateException.class, () -> worker(a::cancel));
        assertTrue(a.cancel());
        assertRejected(first.completeFormatChange(a, ac), DocumentCommandResult.RejectionReason.CANCELLED);
        assertThrows(IllegalStateException.class, () -> first.completeFormatChange(a, ac));
        assertFalse(first.status().dirty());
        assertInstanceOf(DocumentCommandResult.Applied.class, second.completeFormatChange(b, bc));
        assertFalse(b.cancel());
        assertThrows(IllegalStateException.class, () -> second.completeFormatChange(b, bc));
        assertThrows(IllegalStateException.class, b::perform);
    }

    @Test
    void noFinalNewlineAndEmptyDocumentRemainDistinctAndCurrentEncodingCanBeKept() throws Exception {
        for (String original : List.of("", "text", "text\n")) {
            var session = loaded(new MemoryDocumentStorage(), original);
            boolean finalNewline = original.endsWith("\n");
            apply(session, DocumentEncoding.UTF8_BOM, LineEndingPolicy.CRLF);
            assertEquals(finalNewline, session.formatSummary().finalNewline());
            var lines = session.snapshot().lines();
            var prepared = session.prepareFormatChange(session.defaultViewId(), new DocumentFormatSpec(null, LineEndingPolicy.CR));
            session.completeFormatChange(prepared, worker(prepared::perform));
            assertEquals(DocumentEncoding.UTF8_BOM, session.formatSummary().encoding());
            assertEquals(lines, session.snapshot().lines());
        }
    }

    private static void apply(EditorDocumentSession session, DocumentEncoding encoding, LineEndingPolicy policy) throws Exception {
        var prepared = session.prepareFormatChange(session.defaultViewId(), new DocumentFormatSpec(encoding, policy));
        assertInstanceOf(DocumentCommandResult.Applied.class, session.completeFormatChange(prepared, worker(prepared::perform)));
    }

    private static EditorDocumentSession loaded(MemoryDocumentStorage storage, String text) throws Exception {
        storage.put("source", text.getBytes(StandardCharsets.UTF_8));
        var session = EditorDocumentSession.create(new DocumentId("test"), List.of(""));
        session.openPrepared(worker(() -> storage.load(storage.resource("source"), DocumentOpenMode.DETECT, false)));
        return session;
    }

    private static void input(EditorDocumentSession session, String text) {
        assertInstanceOf(DocumentCommandResult.Applied.class,
            session.execute(session.defaultViewId(), new DocumentCommand.Paste(text)));
    }

    private static void assertRejected(DocumentCommandResult result, DocumentCommandResult.RejectionReason reason) {
        assertEquals(reason, assertInstanceOf(DocumentCommandResult.Rejected.class, result).reason());
    }

    private static <T> T worker(Callable<T> action) throws Exception {
        var work = new FutureTask<T>(action);
        var thread = new Thread(work, "format-test-worker");
        thread.start();
        try { return work.get(); }
        catch (java.util.concurrent.ExecutionException error) {
            if (error.getCause() instanceof Exception cause) throw cause;
            if (error.getCause() instanceof Error cause) throw cause;
            throw error;
        } finally { thread.join(); }
    }
}
