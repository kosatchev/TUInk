package dev.tuink.tui.flow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import dev.tuink.core.EditorClipboard;
import dev.tuink.core.TabSession;
import dev.tuink.render.TerminalEditorTextLayout;
import java.io.IOException;
import java.io.File;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SaveAsSuggestionStoreTest {
    @Test
    void automaticDefaultsAreGeneratedLazilyAndRecomputedAfterUntouchedInput() {
        TabSession tab = TabSession.createEmpty(TerminalEditorTextLayout::new, new EditorClipboard());
        SaveAsSuggestionStore store = new SaveAsSuggestionStore();
        AtomicInteger generation = new AtomicInteger();

        String suggestion = store.suggestionFor(tab, () -> "automatic-" + generation.incrementAndGet());
        store.rememberInput(tab, suggestion);

        assertEquals("automatic-1", suggestion);
        assertEquals("automatic-2", store.suggestionFor(tab, () -> "automatic-" + generation.incrementAndGet()));
    }

    @Test
    void rememberedDraftIsNotRenamedWhenItExists(@TempDir Path tempDir) throws IOException {
        Path existing = Files.createFile(tempDir.resolve("chosen.txt"));
        TabSession tab = TabSession.createEmpty(TerminalEditorTextLayout::new, new EditorClipboard());
        SaveAsSuggestionStore store = new SaveAsSuggestionStore();
        store.rememberDraft(tab, existing.toString());

        String suggestion = store.suggestionFor(
            tab,
            () -> { throw new AssertionError("A remembered draft must not generate another target"); }
        );

        assertEquals(existing.toString(), suggestion);
    }

    @Test
    void aRejectedSavedPathFallsBackWithoutErasingTheRememberedChoice(@TempDir Path tempDir) {
        Path source = tempDir.resolve("source.txt");
        Path copy = tempDir.resolve("source-copy.txt");
        TabSession tab = TabSession.createEmpty(TerminalEditorTextLayout::new, new EditorClipboard());
        SaveAsSuggestionStore store = new SaveAsSuggestionStore();
        store.rememberSavedPath(tab, source);

        assertEquals(copy.toString(), store.suggestionFor(tab, copy::toString,
            draft -> !draft.equals(source.toString())));
        store.rememberInput(tab, copy.toString());
        assertEquals(source.toString(), store.suggestionFor(tab, copy::toString));
    }

    @Test
    void aSuitableUserDraftKeepsItsExactNameEvenWhenTheTargetExists(@TempDir Path tempDir) throws IOException {
        Path target = Files.createFile(tempDir.resolve("chosen.txt"));
        TabSession tab = TabSession.createEmpty(TerminalEditorTextLayout::new, new EditorClipboard());
        SaveAsSuggestionStore store = new SaveAsSuggestionStore();
        store.rememberDraft(tab, target.toString());

        assertEquals(target.toString(), store.suggestionFor(tab,
            () -> tempDir.resolve("source-copy.txt").toString(), draft -> draft.equals(target.toString())));
    }

    @Test
    void explicitlyEditingAnAutomaticDefaultMakesItAUserDraft() {
        TabSession tab = TabSession.createEmpty(TerminalEditorTextLayout::new, new EditorClipboard());
        SaveAsSuggestionStore store = new SaveAsSuggestionStore();
        String suggestion = store.suggestionFor(tab, () -> "chosen.txt");
        store.rememberDraft(tab, suggestion);
        store.rememberInput(tab, suggestion);

        assertEquals("chosen.txt", store.suggestionFor(tab, () -> "another.txt"));
    }

    @Test
    void generatedFallbackDoesNotEraseAnUnsuitableManualDraft() {
        TabSession tab = TabSession.createEmpty(TerminalEditorTextLayout::new, new EditorClipboard());
        SaveAsSuggestionStore store = new SaveAsSuggestionStore();
        store.rememberDraft(tab, "manual.txt");
        store.rememberGenerated(tab, "automatic.txt");
        store.rememberInput(tab, "automatic.txt");

        assertEquals("manual.txt", store.suggestionFor(tab, () -> "another.txt"));
    }

    @Test
    void submittedEditedTextIsRememberedAndRemovingATabClearsBothOrigins() {
        TabSession tab = TabSession.createEmpty(TerminalEditorTextLayout::new, new EditorClipboard());
        TabSession other = TabSession.createEmpty(TerminalEditorTextLayout::new, new EditorClipboard());
        SaveAsSuggestionStore store = new SaveAsSuggestionStore();
        store.suggestionFor(tab, () -> "automatic.txt");
        store.rememberInput(tab, "manual.txt");
        store.rememberDraft(other, "other.txt");
        assertEquals("manual.txt", store.suggestionFor(tab, () -> "unused.txt"));
        store.remove(tab);

        assertEquals("fresh.txt", store.suggestionFor(tab, () -> "fresh.txt"));
        assertEquals("other.txt", store.suggestionFor(other, () -> "unused.txt"));
    }

    @Test
    void rawPathsStayReversibleInCurrentSuggestionsRememberedPathsAndCopyFallbacks(@TempDir Path tempDir) {
        assumeTrue(File.separatorChar == '/');
        Path raw = Path.of(URI.create(tempDir.toUri().toASCIIString() + "parent-%FE/legacy-%FF.txt"));
        Path copy = SavePathInput.withNameSuffix(raw, "-copy");
        TabSession tab = TabSession.createEmpty(TerminalEditorTextLayout::new, new EditorClipboard());
        SaveAsSuggestionStore store = new SaveAsSuggestionStore();

        assertEquals(raw, SavePathInput.parse(store.suggestionFor(tab, () -> SavePathInput.format(raw))));
        store.rememberSavedPath(tab, raw);
        String remembered = store.suggestionFor(tab, () -> "untitled.txt");
        assertTrue(remembered.startsWith("file:/"));
        assertEquals(raw, SavePathInput.parse(remembered));
        String fallback = store.suggestionFor(tab, () -> SavePathInput.format(copy), draft -> false);
        assertEquals(copy, SavePathInput.parse(fallback));
    }
}
