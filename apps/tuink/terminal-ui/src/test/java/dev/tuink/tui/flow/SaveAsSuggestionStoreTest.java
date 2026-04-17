package dev.tuink.tui.flow;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.tuink.core.TabSession;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SaveAsSuggestionStoreTest {
    @Test
    void automaticDefaultSkipsExistingFiles(@TempDir Path tempDir) throws IOException {
        Path preferred = Files.createFile(tempDir.resolve("untitled.txt"));
        Files.createFile(tempDir.resolve("untitled-1.txt"));
        SaveAsSuggestionStore store = new SaveAsSuggestionStore();

        String suggestion = store.suggestionFor(
            TabSession.createEmpty(),
            null,
            preferred.toString()
        );

        assertEquals(tempDir.resolve("untitled-2.txt").toString(), suggestion);
    }

    @Test
    void rememberedDraftIsNotRenamedWhenItExists(@TempDir Path tempDir) throws IOException {
        Path existing = Files.createFile(tempDir.resolve("chosen.txt"));
        TabSession tab = TabSession.createEmpty();
        SaveAsSuggestionStore store = new SaveAsSuggestionStore();
        store.rememberDraft(tab, existing.toString());

        String suggestion = store.suggestionFor(
            tab,
            null,
            tempDir.resolve("untitled.txt").toString()
        );

        assertEquals(existing.toString(), suggestion);
    }
}
