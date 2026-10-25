package dev.tuink.tui;

import static dev.tuink.tui.SaveWorkflowTestSupport.finish;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import dev.tuink.core.EditorClipboard;
import dev.tuink.core.TabSession;
import dev.tuink.tui.command.TuinkCommand;
import dev.tuink.tui.flow.ConfirmChoice;
import dev.tuink.tui.flow.SaveAsSuggestionStore;
import dev.tuink.tui.flow.SavePathInput;
import dev.tuink.tui.flow.UiModalState;
import java.io.File;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SaveAsSuggestionWorkflowTest {
    @TempDir Path directory;

    @Test
    void aNewTabSkipsNamedDocumentsWhoseFilesHaveNotBeenCreated() throws Exception {
        TabSession source = emptyTab();
        source.engine().insertInput("new draft");
        SaveWorkflow.Context context = context(new ArrayList<>(List.of(source)), 0);
        Path first = suggested(context, false);
        TabSession owner = open(first);
        context.tabs().add(owner);

        Path next = suggested(context, false);

        assertNotEquals(first, next);
        assertFalse(Files.exists(first, LinkOption.NOFOLLOW_LINKS));
        assertFalse(Files.exists(next, LinkOption.NOFOLLOW_LINKS));
        assertEquals(first, owner.state().getCurrentFile());
        assertTrue(source.state().isDirty());
    }

    @Test
    void copySuggestionsSkipDiskEntriesBrokenLinksAndAnotherTabsParentAlias() throws Exception {
        assumeTrue(Files.getFileStore(directory).supportsFileAttributeView("posix"));
        Path sourcePath = Files.writeString(directory.resolve("source.txt"), "original");
        Path disk = Files.writeString(directory.resolve("source-copy.txt"), "occupied");
        Path broken = Files.createSymbolicLink(directory.resolve("source-copy-1.txt"), Path.of("missing"));
        Path parentAlias = Files.createSymbolicLink(directory.resolve("alias"), Path.of("."));
        Path reserved = directory.resolve("source-copy-2.txt");
        TabSession owner = open(parentAlias.resolve(reserved.getFileName()));
        TabSession source = open(sourcePath);
        source.engine().insertInput("edited ");
        SaveWorkflow.Context context = context(List.of(source, owner), 0);

        Path suggestion = suggested(context, true);
        assertEquals(directory.resolve("source-copy-3.txt"), suggestion);
        var result = finish(context, SaveWorkflow.saveAsInput(context, TuinkCommand.EDIT_INSERT_NEWLINE,
            UiModalState.saveAs(), SavePathInput.format(suggestion)));

        assertTrue(result.modalState().isNone());
        assertEquals("edited original", Files.readString(suggestion));
        assertEquals("original", Files.readString(sourcePath));
        assertEquals("occupied", Files.readString(disk));
        assertTrue(Files.isSymbolicLink(broken));
        assertFalse(Files.exists(reserved));
    }

    @Test
    void untouchedAutomaticCopyIsRecomputedAfterCancelAndAfterFailedSubmission() throws Exception {
        Path sourcePath = Files.writeString(directory.resolve("source.txt"), "original");
        TabSession source = open(sourcePath);
        source.engine().insertInput("edited ");
        List<TabSession> tabs = new ArrayList<>(List.of(source));
        SaveWorkflow.Context context = context(tabs, 0);
        Path first = suggested(context, true);
        SaveWorkflow.saveAsInput(context, TuinkCommand.UI_ESCAPE, UiModalState.saveAs(), first.toString());
        tabs.add(open(first));
        Path second = suggested(context, true);
        assertEquals(directory.resolve("source-copy-1.txt"), second);

        tabs.add(open(second));
        var failed = SaveWorkflow.saveAsInput(context, TuinkCommand.EDIT_INSERT_NEWLINE,
            UiModalState.saveAs(), second.toString());
        assertTrue(failed.notice().message().contains("already open in tab"));
        assertEquals(directory.resolve("source-copy-2.txt"), suggested(context, true));
        assertFalse(Files.exists(first));
        assertFalse(Files.exists(second));
    }

    @Test
    void aUserChosenNameIsPreservedWhenAnotherTabReservesItAndTheFinalGuardRejectsIt() throws Exception {
        Path sourcePath = Files.writeString(directory.resolve("source.txt"), "original");
        TabSession source = open(sourcePath);
        source.engine().insertInput("edited ");
        List<TabSession> tabs = new ArrayList<>(List.of(source));
        SaveWorkflow.Context context = context(tabs, 0);
        Path selected = suggested(context, true);
        context.suggestions().rememberDraft(source, selected.toString());
        tabs.add(open(selected));

        assertEquals(selected, suggested(context, true));
        var result = SaveWorkflow.saveAsInput(context, TuinkCommand.EDIT_INSERT_NEWLINE,
            UiModalState.saveAsForCloseTab(0), selected.toString());

        assertEquals(UiModalState.saveAsForCloseTab(0), result.modalState());
        assertTrue(result.notice().message().contains("already open in tab"));
        assertTrue(result.focusFilename());
        assertTrue(source.state().isDirty());
        assertFalse(Files.exists(selected));
        assertEquals("original", Files.readString(sourcePath));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void quitAndCloseExcludeTheTargetTabEvenBeforeItBecomesActive(boolean quitting) throws Exception {
        TabSession target = emptyTab();
        target.engine().insertInput("draft");
        Path preferred = suggested(context(List.of(target), 0), false);
        TabSession owner = open(preferred);
        SaveWorkflow.Context context = context(List.of(owner, target), 0);

        var result = quitting ? SaveWorkflow.quit(context, ConfirmChoice.SAVE)
            : SaveWorkflow.closeTab(context, UiModalState.closeTabConfirm(1), ConfirmChoice.SAVE);

        assertEquals(1, result.activateTabIndex());
        assertEquals(quitting ? UiModalState.saveAsForQuit() : UiModalState.saveAsForCloseTab(1), result.modalState());
        assertNotEquals(preferred, SavePathInput.parse(result.filename().text()));
        assertFalse(Files.exists(preferred));
        assertEquals(preferred, owner.state().getCurrentFile());
        assertTrue(target.state().isDirty());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void theCurrentOwnedFilenameIsKeptEvenForRecoveryCopiesOrAnAbsentFile(boolean existing) throws Exception {
        Path file = directory.resolve("own.txt");
        if (existing) Files.writeString(file, "original");
        TabSession first = open(file);
        TabSession second = open(file);

        assertEquals(file, suggested(context(List.of(first, second), 0), false));
        assertEquals(file, suggested(context(List.of(first, second), 1), false));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void rawLeafAndParentCopySuggestionsRespectWorkspaceReservationsWithoutChangingBytes(boolean rawParent)
        throws Exception {
        assumeTrue(File.separatorChar == '/');
        Path raw = Path.of(URI.create(directory.toUri().toASCIIString()
            + (rawParent ? "parent-%FE/source.txt" : "source-%FF.txt")));
        Files.createDirectories(raw.getParent());
        Files.writeString(raw, "original");
        Path copy = SavePathInput.withNameSuffix(raw, "-copy");
        Path occupied = SavePathInput.withNameSuffix(copy, "-1");
        Path expected = SavePathInput.withNameSuffix(copy, "-2");
        TabSession source = open(raw);
        TabSession reserved = open(copy);
        Files.writeString(occupied, "occupied");

        Path actual = suggested(context(List.of(source, reserved), 0), true);

        assertEquals(expected, actual);
        assertTrue(SavePathInput.format(actual).startsWith("file:/"));
        assertFalse(Files.exists(copy));
        assertEquals("occupied", Files.readString(occupied));
    }

    @Test
    void readOnlyMaximalAsciiUnicodeRawAndLongExtensionFilesOfferCopiesThatCanActuallyBeSaved() throws Exception {
        assumeTrue(File.separatorChar == '/' && Files.getFileStore(directory).supportsFileAttributeView("posix"));
        List<Path> paths = List.of(
            directory.resolve("a".repeat(251) + ".txt"),
            directory.resolve("中".repeat(83) + ".txt"),
            Path.of(URI.create(directory.toUri().toASCIIString() + "%FF" + "a".repeat(250) + ".txt")),
            directory.resolve("a." + "x".repeat(253))
        );
        for (Path sourcePath : paths) {
            Files.writeString(sourcePath, "original");
            Files.setPosixFilePermissions(sourcePath, PosixFilePermissions.fromString("r--r--r--"));
            TabSession source = open(sourcePath);
            source.engine().insertInput("edited ");
            var action = EditorActionController.decide(TuinkCommand.DOCUMENT_SAVE, true, UiModalState.none(),
                true, true, true, 0, sourcePath, SaveTargetPolicy.isReadOnlyFile(sourcePath));
            assertTrue(action.modalState().isSaveAs());
            var context = context(List.of(source), 0);
            String filename = SaveWorkflow.initialFilename(context, action.saveAsPreset()).text();
            Path target = SavePathInput.parse(filename);
            assertNotEquals(sourcePath, target);
            assertFalse(Files.exists(target));

            var result = finish(context, SaveWorkflow.saveAsInput(context, TuinkCommand.EDIT_INSERT_NEWLINE,
                action.modalState(), filename));

            assertTrue(result.modalState().isNone(), result.notice().message());
            assertEquals("edited original", Files.readString(target));
            assertEquals("original", Files.readString(sourcePath));
            assertFalse(source.state().isDirty());
        }
    }

    @Test
    void maximalCopyNamesSkipTruncatedDiskAndWorkspaceCollisionsIncludingTwoDigitSuffixes() throws Exception {
        Path sourcePath = Files.writeString(directory.resolve("a".repeat(251) + ".txt"), "original");
        Files.writeString(directory.resolve("a".repeat(246) + "-copy.txt"), "occupied");
        for (int number = 1; number <= 9; number++) {
            Files.writeString(directory.resolve("a".repeat(244) + "-copy-" + number + ".txt"), "occupied");
        }
        Path reserved = directory.resolve("a".repeat(243) + "-copy-10.txt");
        TabSession source = open(sourcePath);
        source.engine().insertInput("edited ");
        SaveWorkflow.Context context = context(List.of(source, open(reserved)), 0);

        Path target = suggested(context, true);

        assertEquals(directory.resolve("a".repeat(243) + "-copy-11.txt"), target);
        var result = finish(context, SaveWorkflow.saveAsInput(context, TuinkCommand.EDIT_INSERT_NEWLINE,
            UiModalState.saveAs(), SavePathInput.format(target)));
        assertTrue(result.modalState().isNone(), result.notice().message());
        assertEquals("edited original", Files.readString(target));
        assertEquals("original", Files.readString(sourcePath));
        assertFalse(Files.exists(reserved));
        assertEquals("occupied", Files.readString(directory.resolve("a".repeat(244) + "-copy-9.txt")));
    }

    private static Path suggested(SaveWorkflow.Context context, boolean copy) {
        var effect = SaveWorkflow.initialFilename(context, copy
            ? EditorActionController.SaveAsPreset.SUGGESTED_FROM_CURRENT
            : EditorActionController.SaveAsPreset.FROM_CURRENT_OR_DEFAULT);
        return SavePathInput.parse(effect.text());
    }

    private static SaveWorkflow.Context context(List<TabSession> tabs, int active) {
        return new SaveWorkflow.Context(tabs, active, new SaveAsSuggestionStore());
    }

    private static TabSession emptyTab() {
        return TuiDocumentSession.create(new EditorClipboard()).view();
    }

    private static TabSession open(Path path) throws Exception {
        TabSession tab = emptyTab();
        tab.engine().open(path);
        return tab;
    }
}
