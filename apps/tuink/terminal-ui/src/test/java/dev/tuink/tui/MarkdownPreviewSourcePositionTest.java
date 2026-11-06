package dev.tuink.tui;

import static org.junit.jupiter.api.Assertions.*;

import dev.tuink.core.Position;
import dev.tuink.tui.command.TuinkCommand;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class MarkdownPreviewSourcePositionTest {
    @TempDir Path directory;

    static Stream<Arguments> documentEnds() {
        return Stream.of(Arguments.of("abc", 3), Arguments.of("ab😀", 4),
            Arguments.of("abe\u0301", 4), Arguments.of("**abc**", 5));
    }

    @ParameterizedTest
    @MethodSource("documentEnds")
    void documentEndReturnsToTheSourceInsertionBoundary(String text, int column) throws Exception {
        assertDocumentEnd(text, column, false);
    }

    @ParameterizedTest
    @MethodSource("documentEnds")
    void documentEndReopensTheSourceAtTheInsertionBoundary(String text, int column) throws Exception {
        assertDocumentEnd(text, column, true);
    }

    private void assertDocumentEnd(String text, int column, boolean frozen) throws Exception {
        try (var window = new EditorWindowFixture(directory, "document.md", false, List.of(text))) {
            openPreview(window);
            if (frozen) {
                window.command(TuinkCommand.VIEW_TOGGLE_MARKDOWN_PREVIEW);
                window.command(TuinkCommand.TAB_CLOSE);
                window.await(() -> window.selected() instanceof PreviewTab preview
                    && preview.source() instanceof PreviewTab.ClosedSnapshot && window.preview().ready());
            }
            window.command(TuinkCommand.CURSOR_DOCUMENT_END);
            assertEquals(window.preview().result.document().projection().text().length(), window.preview().cursorOffset());
            window.command(TuinkCommand.VIEW_TOGGLE_MARKDOWN_PREVIEW);
            window.await(() -> window.selected() instanceof TerminalEditorApp.EditorTab && !window.documentIo.busy());
            assertEquals(new Position(0, column), window.active().state().getCursor());
            assertEquals(List.of(text), window.active().tabSession().model().snapshotLines());
            assertFalse(window.active().state().isDirty());
        }
    }

    @Test void paragraphEndReturnsAfterItsLastCharacterBeforeAnUnmappedSeparator() throws Exception {
        try (var window = new EditorWindowFixture(directory, "document.md", false, List.of("abc", "", "next"))) {
            openPreview(window);
            window.command(TuinkCommand.CURSOR_END);
            assertEquals(3, window.preview().cursorOffset());
            window.command(TuinkCommand.VIEW_TOGGLE_MARKDOWN_PREVIEW);
            assertEquals(new Position(0, 3), window.active().state().getCursor());
        }
    }

    @Test void adjacentMappingStartKeepsForwardAffinityAcrossMarkdownDelimiters() throws Exception {
        try (var window = new EditorWindowFixture(directory, "document.md", false, List.of("abc **def**"))) {
            openPreview(window);
            for (int index = 0; index < 4; index++) window.command(TuinkCommand.CURSOR_RIGHT);
            window.command(TuinkCommand.VIEW_TOGGLE_MARKDOWN_PREVIEW);
            assertEquals(new Position(0, 6), window.active().state().getCursor());
        }
    }

    private static void openPreview(EditorWindowFixture window) throws Exception {
        window.command(TuinkCommand.VIEW_TOGGLE_MARKDOWN_PREVIEW);
        window.await(() -> window.preview().ready());
    }
}
