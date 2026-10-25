package dev.tuink.compat;

import dev.tuink.core.SearchMatcher;
import dev.tuink.core.SearchOptions;
import dev.tuink.core.SearchWorkBudget;
import dev.tuink.editor.document.DocumentId;
import dev.tuink.editor.document.DocumentSnapshot;
import dev.tuink.editor.document.ImmutableText;
import dev.tuink.editor.document.TextPosition;
import dev.tuink.editor.document.TextEdit;
import dev.tuink.editor.document.TextRange;
import dev.tuink.editor.document.WorkspaceEdit;
import dev.tuink.editor.session.document.EditorDocumentSession;
import dev.tuink.editor.session.document.WorkspaceEditResult;
import dev.tuink.editor.highlight.HighlightRequest;
import dev.tuink.editor.highlight.SemanticStyleKey;
import dev.tuink.highlight.regex.RegexHighlighter;
import dev.tuink.unicode.GraphemeText;
import java.util.List;

/** External consumer of the shared engine without terminal, JGit or FFM adapters. */
public final class PortableEngineProbe {
    public static void main(String[] args) {
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
        System.out.println("PASS: shared engine (edit, undo/redo, stale guards, search, snapshots, highlighting, graphemes)");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
