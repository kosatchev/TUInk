package dev.tuink.compat;

import dev.tuink.core.SearchMatcher;
import dev.tuink.editor.search.SearchOptions;
import dev.tuink.core.SearchWorkBudget;
import dev.tuink.editor.document.DocumentId;
import dev.tuink.editor.command.DocumentCommand;
import dev.tuink.editor.command.DocumentCommandResult;
import dev.tuink.editor.document.DocumentSnapshot;
import dev.tuink.editor.document.ImmutableText;
import dev.tuink.editor.document.TextPosition;
import dev.tuink.editor.document.TextEdit;
import dev.tuink.editor.document.TextRange;
import dev.tuink.editor.document.WorkspaceEdit;
import dev.tuink.editor.session.document.EditorDocumentSession;
import dev.tuink.editor.session.document.EditorWorkspace;
import dev.tuink.editor.session.document.DocumentIoCoordinator;
import dev.tuink.editor.session.document.PreparedDocumentOpen;
import dev.tuink.editor.session.document.WorkspaceEditResult;
import dev.tuink.editor.session.recovery.RecoveryCoordinator;
import dev.tuink.editor.session.recovery.RecoveryParticipant;
import dev.tuink.editor.session.recovery.RecoveryPersistence;
import dev.tuink.editor.session.recovery.PreparedCheckpoint;
import dev.tuink.editor.recovery.RecoveryDocument;
import dev.tuink.editor.storage.DocumentOpenMode;
import dev.tuink.editor.storage.SaveDisposition;
import dev.tuink.verification.MemoryDocumentStorage;
import dev.tuink.editor.highlight.HighlightRequest;
import dev.tuink.editor.highlight.SemanticStyleKey;
import dev.tuink.highlight.regex.RegexHighlighter;
import dev.tuink.unicode.GraphemeText;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Executors;

/** External consumer of the shared engine without terminal, JGit or FFM adapters. */
public final class PortableEngineProbe {
    public static void main(String[] args) throws Exception {
        ImmutableText text = ImmutableText.of("a".repeat(100_000))
            .replace(50_000, 50_001, "😀");
        DocumentSnapshot snapshot = DocumentSnapshot.fromImmutableLines(
            new DocumentId("external-consumer"), 1, List.of(text));
        require(snapshot.lineText(0) == text, "snapshot must share immutable text");
        require(!snapshot.isCodePointBoundary(new TextPosition(0, 50_001)), "UTF-16 boundary");
        require(text.replace(0, 1, "z").charAt(0) == 'z' && snapshot.lineText(0).charAt(0) == 'a',
            "persistent text ownership");

        EditorDocumentSession session = EditorDocumentSession.create(new DocumentId("headless-engine"),
            List.of("original😀", "tail"));
        long originalRevision = session.snapshot().revision();
        WorkspaceEdit edit = new WorkspaceEdit(session.documentId(), originalRevision, List.of(
            new TextEdit(new TextRange(new TextPosition(0, 0), new TextPosition(0, 8)), "changed")));
        require(session.applyWorkspaceEdit(edit) instanceof WorkspaceEditResult.Applied, "workspace edit");
        require(session.snapshot().line(0).equals("changed😀"), "edited text");
        require(session.undo() && session.snapshot().line(0).equals("original😀"), "undo");
        require(session.redo() && session.snapshot().line(0).equals("changed😀"), "redo");
        require(session.applyWorkspaceEdit(edit) instanceof WorkspaceEditResult.Rejected, "stale edit");
        int matches = SearchWorkBudget.run(10_000, () -> SearchMatcher.compile("changed", SearchOptions.DEFAULT)
            .countMatches(session.snapshot().lineText(0)));
        require(matches == 1, "headless search");

        String java = "class Probe { String value = \"text\"; // comment";
        var tokens = RegexHighlighter.forJava().highlight(java,
            new HighlightRequest(0, java.length(), 4_096, 256, 1_000_000));
        require(tokens.stream().anyMatch(token -> token.styleKey().equals(SemanticStyleKey.KEYWORD)),
            "semantic keyword");
        require(tokens.stream().anyMatch(token -> token.styleKey().equals(SemanticStyleKey.STRING)),
            "semantic string");

        GraphemeText graphemes = GraphemeText.of("e\u0301👩‍💻");
        require(graphemes.graphemeCount() == 2, "grapheme segmentation");
        commandRoundTrip();
        workspaceRoundTrip();
        storageRoundTrip();
        System.out.println("PASS: shared engine (edit, undo/redo, stale guards, search, snapshots, highlighting, graphemes, workspace, portable I/O)");
    }

    private static void workspaceRoundTrip() throws Exception {
        var workspace = new EditorWorkspace<String>();
        var document = EditorDocumentSession.create(new DocumentId("portable-workspace"), List.of("abcdef"));
        var first = document.defaultViewId();
        var second = document.attachView();
        workspace.add(document, first, "first layout");
        workspace.addAll(List.of(new EditorWorkspace.Entry<>(document, second, "second layout")));
        require(workspace.active().viewId().equals(first), "batch admission preserves selection");
        document.execute(first, new DocumentCommand.MoveTo(new TextPosition(0, 3), false));
        document.execute(first, new DocumentCommand.Input("X"));
        document.execute(second, new DocumentCommand.MoveTo(new TextPosition(0, 1), false));
        var deferred = document.commandTarget(first);
        workspace.select(second);
        workspace.remove(first);
        require(workspace.active().viewId().equals(second) && workspace.activeIndex() == 0,
            "retiring another view preserves selected identity");
        require(document.execute(deferred, new DocumentCommand.Input("stale"))
            instanceof DocumentCommandResult.Rejected rejection
            && rejection.reason() == DocumentCommandResult.RejectionReason.UNKNOWN_VIEW, "retired view command");
        document.execute(second, new DocumentCommand.Edit(DocumentCommand.EditAction.UNDO));
        require(document.snapshot().line(0).equals("abcdef")
            && document.view(second).cursor().equals(new TextPosition(0, 1)), "shared history and independent cursor");
        document.execute(second, new DocumentCommand.Edit(DocumentCommand.EditAction.REDO));
        require(document.snapshot().line(0).equals("abcXdef"), "redo after first view closes");
        require(document.prepareRecovery(workspace.active().viewId()).materialize().lines().equals(document.snapshot().lines()),
            "recovery uses a surviving view");
        var foreignWorkspace = new EditorWorkspace<String>();
        try {
            foreignWorkspace.add(document, second, "foreign owner");
            throw new AssertionError("Document admitted by two workspaces");
        } catch (IllegalStateException expected) {
            require(foreignWorkspace.entries().isEmpty() && document.workspace() == workspace, "exclusive workspace owner");
        }
        var foreignThread = new java.util.concurrent.FutureTask<>(() -> {
            try {
                workspace.remove(second);
                return false;
            } catch (IllegalStateException expected) { return true; }
        });
        new Thread(foreignThread, "portable-workspace-owner-probe").start();
        require(foreignThread.get(), "workspace thread ownership");
        workspace.remove(second);
        require(workspace.active() == null && document.workspace() == null, "last view retires membership");
    }

    private static void commandRoundTrip() {
        var session = EditorDocumentSession.create(new DocumentId("portable-commands"), List.of("original😀", "tail"));
        var view = session.defaultViewId();
        var target = session.commandTarget(view);
        var input = session.execute(target, new DocumentCommand.Input("X"));
        require(input instanceof DocumentCommandResult.Applied applied && applied.textChanged(), "typed input command");
        session.execute(view, new DocumentCommand.Input("Y"));
        require(session.execute(target, new DocumentCommand.Input("stale")) instanceof DocumentCommandResult.Rejected,
            "stale command must be rejected");
        var undo = new DocumentCommand.Edit(DocumentCommand.EditAction.UNDO);
        var redo = new DocumentCommand.Edit(DocumentCommand.EditAction.REDO);
        session.execute(view, undo);
        require(session.snapshot().line(0).equals("original😀"), "command typing coalescing and undo");
        session.execute(view, redo);
        require(session.snapshot().line(0).equals("XYoriginal😀"), "command redo");
        var replaced = session.execute(view, new DocumentCommand.Search(DocumentCommand.SearchAction.REPLACE_ALL,
            "original", "changed", dev.tuink.editor.search.SearchOptions.DEFAULT));
        require(replaced instanceof DocumentCommandResult.Applied applied && applied.affectedCount() == 1,
            "command replacement count");
        require(session.snapshot().line(0).equals("XYchanged😀"), "command replacement text");
        session.execute(view, undo);
        require(session.snapshot().line(0).equals("XYoriginal😀"), "command replacement undo");
    }

    /** Public session I/O with a provider compiled only against the shared libraries. */
    private static void storageRoundTrip() throws Exception {
        MemoryDocumentStorage storage = new MemoryDocumentStorage();
        byte[] original = "\uFEFFalpha😀\r\nbeta\ngamma\r".getBytes(StandardCharsets.UTF_8);
        storage.put("source.java", original);
        var source = storage.resource("source.java");
        var target = storage.resource("copy.java");
        var session = EditorDocumentSession.create(new DocumentId("portable-storage"), List.of(""));
        var worker = Executors.newSingleThreadExecutor();
        try {
            var open = new PreparedDocumentOpen(source, DocumentOpenMode.DETECT, false);
            var loaded = open.accept(worker.submit(open::read).get());
            int calls = storage.calls();
            session.openPrepared(loaded);
            require(storage.calls() == calls, "open publication must not call storage");
            require(session.snapshot().lines().equals(List.of("alpha😀", "beta", "gamma", "")),
                "portable read and final EOL");
            require(session.status().metadata().lineSeparators().equals(List.of("\r\n", "\n", "\r")),
                "mixed EOL metadata");
            require(!session.status().dirty() && session.status().binding().version() != null,
                "loaded binding and savepoint");

            TextPosition start = new TextPosition(0, 0);
            WorkspaceEdit edit = new WorkspaceEdit(session.documentId(), session.snapshot().revision(), List.of(
                new TextEdit(new TextRange(start, start), "draft ")));
            require(session.applyWorkspaceEdit(edit) instanceof WorkspaceEditResult.Applied applied && applied.changed(),
                "portable storage edit");
            require(session.status().dirty(), "edit must mark the loaded document dirty");
            long revision = session.snapshot().revision();
            var io = session.io();
            var inspect = io.beginSaveAs(target, SaveDisposition.CREATE_NEW, false);
            require(storage.calls() == calls, "save capture must not call storage");
            require(session.execute(session.defaultViewId(), new DocumentCommand.Input("blocked"))
                instanceof DocumentCommandResult.Rejected blocked
                && blocked.reason() == DocumentCommandResult.RejectionReason.DOCUMENT_BUSY,
                "coordinator must lock document mutations");
            var inspected = worker.submit(inspect::perform).get();
            calls = storage.calls();
            var observation = io.accept(inspect, inspected);
            require(observation instanceof DocumentIoCoordinator.Approval, "inspection yields exact approval");
            var confirmation = ((DocumentIoCoordinator.Approval) observation).confirmation();
            require(!confirmation.exists() && !confirmation.currentTarget(), "new Save As target");
            var ready = (DocumentIoCoordinator.Ready) io.approve(confirmation);
            require(storage.calls() == calls, "save approval must not call storage");
            var written = worker.submit(ready.work()::perform).get();
            require(session.status().dirty() && session.status().binding().resource().equals(source),
                "worker write must not publish owner state");
            calls = storage.calls();
            require(io.accept(ready.work(), written) instanceof DocumentIoCoordinator.Saved, "owner save completion");
            require(storage.calls() == calls, "save completion must not call storage");
            require(!session.status().dirty() && session.status().binding().resource().equals(target),
                "completed Save As binding and savepoint");
            require(session.snapshot().revision() == revision, "saving must preserve text revision");
            require(Arrays.equals(original, storage.bytes("source.java")), "Save As must retain source bytes");
            byte[] saved = "\uFEFFdraft alpha😀\r\nbeta\ngamma\r".getBytes(StandardCharsets.UTF_8);
            require(Arrays.equals(saved, storage.bytes("copy.java")), "Save As must retain BOM and mixed EOL bytes");
            require(session.undo() && session.status().dirty() && session.snapshot().line(0).equals("alpha😀"),
                "undo after Save As must retain text history");
            require(session.redo() && !session.status().dirty()
                && session.snapshot().line(0).equals("draft alpha😀"), "redo must return to the saved state");
            require(Arrays.equals(saved, storage.bytes("copy.java")) && Arrays.equals(original, storage.bytes("source.java")),
                "history operations must not write resources");
            session.undo();
            var dirty = session.status();
            storage.failNextWrite(MemoryDocumentStorage.WriteFailure.AFTER_COMMIT);
            var uncertain = io.beginSave();
            var uncertainResult = io.accept(uncertain, worker.submit(uncertain::perform).get());
            require(uncertainResult instanceof DocumentIoCoordinator.Failed failure
                && failure.kind() == DocumentIoCoordinator.FailureKind.UNKNOWN, "uncertain commit remains explicit");
            require(session.status().equals(dirty) && session.status().dirty(), "uncertain write retains savepoint and draft");
            var checkpoint = session.prepareRecovery(session.defaultViewId());
            var draft = worker.submit(checkpoint::materialize).get();
            require(draft.dirty() && draft.lines().equals(session.snapshot().lines()), "neutral recovery capture");
            var persisted = new java.util.concurrent.ConcurrentHashMap<String, RecoveryDocument>();
            var released = new java.util.concurrent.atomic.AtomicBoolean();
            var recovery = new RecoveryCoordinator<RecoveryDocument>(new RecoveryPersistence<>() {
                public void save(String id, RecoveryDocument value) { persisted.put(id, value); }
                public void delete(String id) { persisted.remove(id); }
                public void release(String id) { released.set(true); }
            }, worker, System::nanoTime, () -> "portable-draft");
            RecoveryParticipant<RecoveryDocument> participant = new RecoveryParticipant<>() {
                public DocumentId documentId() { return session.documentId(); }
                public Status status() { return new Status(session.status().dirty(), session.status().recoveryRevision()); }
                public PreparedCheckpoint<RecoveryDocument> prepareCheckpoint() {
                    var captured = session.prepareRecovery(session.defaultViewId());
                    return captured::materialize;
                }
            };
            recovery.synchronize(List.of(participant), RecoveryCoordinator.RemovalDisposition.PRESERVE);
            recovery.tick(System.nanoTime());
            worker.submit(() -> {}).get();
            recovery.tick(System.nanoTime());
            require(persisted.get("portable-draft").lines().equals(draft.lines()), "portable recovery persistence");
            recovery.synchronize(List.of(), RecoveryCoordinator.RemovalDisposition.PRESERVE);
            recovery.tick(System.nanoTime());
            worker.submit(() -> {}).get();
            recovery.tick(System.nanoTime());
            require(released.get() && persisted.containsKey("portable-draft"), "detach preserves persisted recovery");
            require(recovery.deleteEntries(List.of("portable-draft")).successful() && persisted.isEmpty(),
                "explicit discard deletes detached recovery");
        } finally {
            worker.shutdownNow();
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
