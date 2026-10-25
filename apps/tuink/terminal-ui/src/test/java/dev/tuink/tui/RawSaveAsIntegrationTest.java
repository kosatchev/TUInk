package dev.tuink.tui;

import dev.tuink.storage.jvm.JvmDocumentStorage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import dev.tuiframe.input.KeyStroke;
import dev.tuiframe.input.TextInput;
import dev.tuink.tui.command.TuinkCommand;
import dev.tuink.tui.flow.SavePathInput;
import java.io.File;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class RawSaveAsIntegrationTest {
    @TempDir Path directory;

    @ParameterizedTest
    @ValueSource(strings = {"line\nbreak.txt", "line\rbreak.txt", "tab\tname.txt", "control\u0001.txt", "next\u0085line.txt"})
    void cuttingAndPastingAControlCharacterPathStillSavesTheOriginalFile(String name) throws Exception {
        assumeUnix();
        Path original = Files.writeString(directory.resolve(name), "original");
        try (EditorWindowFixture window = new EditorWindowFixture(directory, false, List.of("CLI"))) {
            window.active().tabSession().engine().openExisting(JvmDocumentStorage.resource(original));
            window.frame();
            window.event(new TextInput("edited "));
            window.command(TuinkCommand.DOCUMENT_SAVE_AS);
            String suggestion = window.filename.text();
            assertTrue(suggestion.startsWith("file:/"));
            assertTrue(suggestion.codePoints().noneMatch(Character::isISOControl));

            window.event(KeyStroke.of(KeyStroke.Key.A, KeyStroke.Modifier.CTRL));
            window.command(TuinkCommand.EDIT_CUT);
            window.command(TuinkCommand.EDIT_PASTE);
            assertEquals(suggestion, window.filename.text());
            window.event(KeyStroke.of(KeyStroke.Key.ENTER));

            assertEquals("edited original", Files.readString(original));
            assertEquals(original, JvmDocumentStorage.pathOf(window.active().state().getCurrentResource()));
            assertFalse(window.active().state().isDirty());
            try (var files = Files.list(directory)) {
                assertEquals(2, files.count(), "Save As must not create a renamed neighbor");
            }
        }
    }

    @ParameterizedTest
    @CsvSource({"false,false", "false,true", "true,false", "true,true"})
    void submittingTheDefaultRawFileOrParentPathSavesTheSameDocument(boolean rawParent, boolean neighborExists)
        throws Exception {
        assumeUnix();
        Path original;
        Path neighbor;
        if (rawParent) {
            Path parent = Files.createDirectory(rawPath("parent-%FE"));
            original = Files.writeString(parent.resolve("original.txt"), "original");
            neighbor = directory.resolve("parent-\uFFFD/original.txt");
        } else {
            original = Files.writeString(rawPath("legacy-%FF.txt"), "original");
            neighbor = directory.resolve("legacy-\uFFFD.txt");
        }
        if (neighborExists) {
            Files.createDirectories(neighbor.getParent());
            Files.writeString(neighbor, "original");
        }
        try (EditorWindowFixture window = new EditorWindowFixture(directory, false, List.of("CLI"))) {
            window.active().tabSession().engine().openExisting(JvmDocumentStorage.resource(original));
            window.frame();
            window.event(new TextInput("edited "));
            window.command(TuinkCommand.DOCUMENT_SAVE_AS);
            String suggestion = window.filename.text();
            assertTrue(suggestion.startsWith("file:/"));
            assertEquals(original, SavePathInput.parse(suggestion));

            // Copy, cut and paste must retain the URI's percent-encoded native bytes.
            window.event(KeyStroke.of(KeyStroke.Key.A, KeyStroke.Modifier.CTRL));
            window.command(TuinkCommand.EDIT_COPY);
            assertEquals(suggestion, window.host.clipboard.text());
            window.command(TuinkCommand.EDIT_CUT);
            assertEquals("", window.filename.text());
            window.command(TuinkCommand.EDIT_PASTE);
            assertEquals(suggestion, window.filename.text());
            window.event(KeyStroke.of(KeyStroke.Key.ENTER));

            assertEquals("edited original", Files.readString(original));
            assertEquals(original, JvmDocumentStorage.pathOf(window.active().state().getCurrentResource()));
            assertFalse(window.active().state().isDirty());
            if (neighborExists) {
                assertEquals("original", Files.readString(neighbor));
            } else {
                assertFalse(Files.exists(neighbor));
            }
            window.command(TuinkCommand.DOCUMENT_SAVE_AS);
            assertEquals(original, SavePathInput.parse(window.filename.text()), "the remembered saved path must also be reversible");
            window.event(KeyStroke.of(KeyStroke.Key.ESCAPE));
        }
    }

    @Test
    void aReadOnlyRawNameAndOccupiedCopyGetAReversibleNumberedCopySuggestion() throws Exception {
        assumeUnix();
        Path original = Files.writeString(rawPath("legacy-%FF.txt"), "original");
        Path occupied = Files.writeString(rawPath("legacy-%FF-copy.txt"), "occupied");
        Path expected = rawPath("legacy-%FF-copy-1.txt");
        Path wrong = directory.resolve("legacy-\uFFFD-copy-1.txt");
        try (EditorWindowFixture window = new EditorWindowFixture(directory, false, List.of("CLI"))) {
            window.active().tabSession().engine().openExisting(JvmDocumentStorage.resource(original));
            window.frame();
            window.event(new TextInput("edited "));
            Files.setPosixFilePermissions(original, PosixFilePermissions.fromString("r--------"));
            assumeTrue(!Files.isWritable(original));

            window.command(TuinkCommand.DOCUMENT_SAVE);

            assertEquals(expected, SavePathInput.parse(window.filename.text()));
            window.event(KeyStroke.of(KeyStroke.Key.ENTER));
            assertEquals("edited original", Files.readString(expected));
            assertEquals("original", Files.readString(original));
            assertEquals("occupied", Files.readString(occupied));
            assertFalse(Files.exists(wrong));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void invalidExplicitUrisKeepTheDraftAndQuitOrCloseContinuation(boolean quitting) throws Exception {
        try (EditorWindowFixture window = new EditorWindowFixture(directory, false, List.of("original"))) {
            window.ui.confirmOnCloseSetting = true;
            window.command(TuinkCommand.TAB_NEW);
            window.event(new TextInput("draft"));
            window.command(quitting ? TuinkCommand.APP_QUIT : TuinkCommand.TAB_CLOSE);
            window.event(new TextInput("y"));
            for (String malformed : List.of("file:///bad%zz", "file:///bad%00", "file:///tmp/file?query")) {
                window.event(KeyStroke.of(KeyStroke.Key.A, KeyStroke.Modifier.CTRL));
                window.event(new TextInput(malformed));
                window.event(KeyStroke.of(KeyStroke.Key.ENTER));

                assertEquals(malformed, window.filename.text());
                assertTrue(window.notification.message.contains("invalid target path"));
                assertTrue(window.screenRow(window.height - 2).startsWith("Save As: "));
                assertTrue(window.controller.running());
                assertEquals(2, window.tabs.size());
                assertTrue(window.active().state().isDirty());
            }
            Path target = directory.resolve("valid retry.txt");
            window.event(KeyStroke.of(KeyStroke.Key.A, KeyStroke.Modifier.CTRL));
            window.event(new TextInput(target.toUri().toASCIIString()));
            window.event(KeyStroke.of(KeyStroke.Key.ENTER));

            assertEquals("draft", Files.readString(target));
            assertEquals(!quitting, window.controller.running());
            assertEquals(quitting ? 2 : 1, window.tabs.size());
            assertEquals("original", Files.readString(window.document));
        }
    }

    private void assumeUnix() throws Exception {
        assumeTrue(File.separatorChar == '/' && Files.getFileStore(directory).supportsFileAttributeView("posix"));
    }

    private Path rawPath(String encodedName) {
        return Path.of(URI.create(directory.toUri().toASCIIString() + encodedName));
    }
}
