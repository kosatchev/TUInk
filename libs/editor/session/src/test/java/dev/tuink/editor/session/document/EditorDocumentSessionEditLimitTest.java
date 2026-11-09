package dev.tuink.editor.session.document;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tuink.core.EditorEngine;
import dev.tuink.core.TextModel;
import dev.tuink.editor.document.DocumentId;
import dev.tuink.editor.document.TextEdit;
import dev.tuink.editor.document.TextPosition;
import dev.tuink.editor.document.TextRange;
import dev.tuink.editor.document.WorkspaceEdit;
import dev.tuink.editor.session.edit.WorkspaceEditValidator;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class EditorDocumentSessionEditLimitTest {
    @Test
    void oversizedWorkspaceEditReturnsATypedRejectionAndPreservesSnapshotAndRedo() throws Exception {
        String classpath = String.join(File.pathSeparator, location(EditorDocumentSessionEditLimitTest.class),
            location(EditorDocumentSession.class), location(EditorEngine.class), location(dev.tuink.core.SearchMatcher.class), location(TextModel.class),
            location(WorkspaceEditValidator.class),
            location(DocumentId.class), location(Class.forName("dev.tuink.unicode.GraphemeText")));
        Process process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "-Xmx64m", "-cp", classpath, MemoryProbe.class.getName()).redirectErrorStream(true).start();
        try {
            assertTrue(process.waitFor(20, TimeUnit.SECONDS), "Workspace edit admission probe exceeded its deadline");
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertEquals(0, process.exitValue(), output);
        } finally {
            process.destroyForcibly();
        }
    }

    private static String location(Class<?> type) throws Exception {
        return Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
    }

    public static final class MemoryProbe {
        public static void main(String[] args) {
            DocumentId id = new DocumentId("limited");
            EditorDocumentSession session = EditorDocumentSession.create(id, List.of("a".repeat(128)));
            session.applyWorkspaceEdit(new WorkspaceEdit(id, session.snapshot().revision(), List.of(edit(0, 0, "X"))));
            require(session.undo(), "Could not prepare redo");
            var before = session.snapshot();
            String replacement = "b".repeat(1_000_000);
            List<TextEdit> edits = new ArrayList<>();
            for (int index = 0; index < 128; index++) edits.add(edit(index, index + 1, replacement));
            var result = session.applyWorkspaceEdit(new WorkspaceEdit(id, before.revision(), edits));
            require(result instanceof WorkspaceEditResult.Rejected, "Oversized workspace edit was accepted");
            var rejected = (WorkspaceEditResult.Rejected) result;
            require(rejected.reason() == WorkspaceEditResult.RejectionReason.EDIT_LIMIT_EXCEEDED,
                "Workspace layer lost the typed core reason");
            require(rejected.currentSnapshot() == before && session.snapshot() == before,
                "Refusal changed the published snapshot/revision");
            require(rejected.violations().isEmpty(), "Memory admission is not range validation");
            require(session.redo(), "Refusal discarded redo");
            require(session.snapshot().line(0).equals("X" + "a".repeat(128)), "Redo content changed");
            require(session.undo() && session.snapshot().lines().equals(before.lines()), "Redo cannot be undone");
            require(!session.undo(), "Refusal manufactured undo history");
        }

        private static TextEdit edit(int start, int end, String replacement) {
            return new TextEdit(new TextRange(new TextPosition(0, start), new TextPosition(0, end)), replacement);
        }

        private static void require(boolean condition, String message) {
            if (!condition) throw new AssertionError(message);
        }
    }
}
