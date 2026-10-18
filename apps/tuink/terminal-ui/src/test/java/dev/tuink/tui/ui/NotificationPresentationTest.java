package dev.tuink.tui.ui;

import static org.junit.jupiter.api.Assertions.*;

import dev.tuiframe.cell.CellText;
import dev.tuink.tui.flow.SavePathInput;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class NotificationPresentationTest {
    @TempDir Path directory;

    @Test
    void controlsAreVisiblePlainTextAndRepeatedPresentationDoesNotDoubleEscape() {
        String raw = "A\nB\rC\tD\u001B\u0000\u007F\u0085界👩‍💻é";
        String expected = "A\\nB\\rC\\tD\\u001B\\u0000\\u007F\\u0085界👩‍💻é";
        assertEquals(expected, NotificationPresentation.singleLine(raw));
        assertEquals(expected, NotificationPresentation.singleLine(expected));
        assertEquals(expected, NotificationBar.of(raw, null).text());
        assertEquals(1, NotificationBar.of(raw, null).height());
        assertFalse(NotificationBar.of("\n", null).text().contains("\n"));
    }

    @Test
    void fittingKeepsTheReasonPrefixWithinCellBoundsWithoutSplittingWideGlyphs() {
        for (int width = 1; width < 30; width++) {
            String fitted = NotificationPresentation.fit("permission denied: 界👩‍💻file.txt", width);
            assertTrue(CellText.width(fitted) <= width, fitted);
            assertTrue(fitted.codePoints().noneMatch(Character::isISOControl));
        }
        assertEquals("permission denied: 界👩‍💻file.txt",
            NotificationPresentation.fit("permission denied: 界👩‍💻file.txt", 80));
    }

    @Test
    void controlAndRawBytePathsHaveLosslessSafeDisplayWithoutRenamingAnything() throws Exception {
        Path control = Files.writeString(directory.resolve("line\nbreak.txt"), "content");
        String display = NotificationPresentation.path(control);
        assertEquals(control, SavePathInput.parse(display));
        assertTrue(display.contains("%0A"));
        assertTrue(display.codePoints().noneMatch(Character::isISOControl));
        assertEquals("content", Files.readString(control));
        if (directory.getFileSystem().getSeparator().equals("/")) {
            Path raw = Path.of(URI.create(directory.toUri().toASCIIString() + "name-%FF.txt"));
            Files.writeString(raw, "raw content");
            assertEquals(raw, SavePathInput.parse(NotificationPresentation.path(raw)));
            assertEquals("raw content", Files.readString(raw));
        }
    }
}
