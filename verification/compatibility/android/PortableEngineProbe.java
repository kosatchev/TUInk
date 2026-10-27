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
import dev.tuink.editor.session.document.WorkspaceEditResult;
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
        storageRoundTrip();
        System.out.println("PASS: shared engine (edit, undo/redo, stale guards, search, snapshots, highlighting, graphemes, portable I/O)");
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
            var loaded = worker.submit(() -> storage.load(source, DocumentOpenMode.DETECT, false)).get();
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
            var captured = session.prepareSaveAsInput(target, SaveDisposition.CREATE_NEW, false);
            require(storage.calls() == calls, "save capture must not call storage");
            var inspected = worker.submit(captured::inspectTarget).get();
            require(!inspected.exists() && !inspected.currentTarget(), "new Save As target");
            calls = storage.calls();
            var approved = session.approveSaveAs(inspected);
            require(storage.calls() == calls, "save approval must not call storage");
            var receipt = worker.submit(approved::write).get();
            require(session.status().dirty() && session.status().binding().resource().equals(source),
                "worker write must not publish owner state");
            calls = storage.calls();
            session.completeSaved(approved, receipt);
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
        } finally {
            worker.shutdownNow();
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
