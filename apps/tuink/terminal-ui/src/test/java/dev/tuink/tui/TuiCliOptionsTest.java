package dev.tuink.tui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tuink.editor.storage.DocumentOpenMode;
import dev.tuink.tui.style.UiStyles;
import java.nio.file.Path;
import java.net.URI;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class TuiCliOptionsTest {
    @Test
    void explicitUtf16ChoiceAppliesOnlyToTheRequestedCommandLineFile() {
        assertEquals(DocumentOpenMode.UTF_16LE,
            TuiCliOptions.parse(new String[] {"--open-as-utf16le", "file.txt"}).openMode());
        assertEquals(DocumentOpenMode.DETECT, TuiCliOptions.parse(new String[] {"file.txt"}).openMode());
        assertThrows(IllegalArgumentException.class,
            () -> TuiCliOptions.parse(new String[] {"--open-as-utf16le"}));
        assertEquals(Path.of("--open-as-utf16le"),
            TuiCliOptions.parse(new String[] {"--", "--open-as-utf16le"}).file());
    }

    @Test
    void acceptsEveryOpeningEncodingInBothArgumentForms() {
        String[] names = {"auto", "utf-8", "utf-16le", "utf-16be", "windows-1251", "cp866", "koi8-r"};
        DocumentOpenMode[] modes = {DocumentOpenMode.DETECT, DocumentOpenMode.UTF_8, DocumentOpenMode.UTF_16LE,
            DocumentOpenMode.UTF_16BE, DocumentOpenMode.WINDOWS_1251, DocumentOpenMode.CP866, DocumentOpenMode.KOI8_R};
        for (int index = 0; index < names.length; index++) {
            for (String[] arguments : new String[][] {
                {"--open-as=" + names[index], "file.txt"}, {"--open-as", names[index], "file.txt"}
            }) {
                TuiCliOptions options = TuiCliOptions.parse(arguments);
                assertEquals(modes[index], options.openMode());
                assertEquals(Path.of("file.txt"), options.file());
            }
        }
    }

    @Test
    void explicitOpeningOptionsRequireAValueAndAFile() {
        assertEquals("Missing value after --open-as", assertThrows(IllegalArgumentException.class,
            () -> TuiCliOptions.parse(new String[] {"--open-as"})).getMessage());
        for (String[] arguments : new String[][] {
            {"--open-as="}, {"--open-as=unknown", "file.txt"}, {"--open-as", "--", "file.txt"},
            {"--open-as", "--ui=gui", "file.txt"}, {"--open-as=utf-8"}, {"--open-as", "cp866"}
        }) {
            assertThrows(IllegalArgumentException.class, () -> TuiCliOptions.parse(arguments));
        }
        assertTrue(TuiCliOptions.parse(new String[] {"--open-as=koi8-r", "--help"}).showHelp());
    }

    @Test
    void openingModeIsPerInvocationAndTheLastExplicitChoiceWins() {
        assertEquals(DocumentOpenMode.CP866,
            TuiCliOptions.parse(new String[] {"--open-as-utf16le", "--open-as", "cp866", "file.txt"}).openMode());
        assertEquals(DocumentOpenMode.UTF_16LE,
            TuiCliOptions.parse(new String[] {"--open-as=cp866", "--open-as-utf16le", "file.txt"}).openMode());
        assertEquals(DocumentOpenMode.DETECT, TuiCliOptions.parse(new String[] {"file.txt"}).openMode());
        TuiCliOptions literal = TuiCliOptions.parse(new String[] {"--open-as", "cp866", "--", "--open-as=utf-8"});
        assertEquals(Path.of("--open-as=utf-8"), literal.file());
        assertEquals(DocumentOpenMode.CP866, literal.openMode());
    }

    @Test
    void defaultsLeaveOptionalOverridesUnset() {
        TuiCliOptions options = TuiCliOptions.parse(new String[0]);

        assertNull(options.file());
        assertNull(options.mouseEnabledOverride());
        assertTrue(options.altScreenEnabled());
        assertTrue(options.colorEnabled());
        assertNull(options.readModeOverride());
        assertFalse(options.showHelp());
        assertFalse(options.showVersion());
        assertNull(options.themeOverride());
        assertFalse(options.profileEnabled());
        assertNull(options.profileFile());
        assertEquals(DocumentOpenMode.DETECT, options.openMode());
    }

    @Test
    void parsesFrontendFlagsAndFile() {
        TuiCliOptions options = TuiCliOptions.parse(new String[] {
            "--no-mouse",
            "--no-alt-screen",
            "--no-color",
            "--read",
            "--theme=light",
            "notes.txt"
        });

        assertEquals(Path.of("notes.txt"), options.file());
        assertEquals(Boolean.FALSE, options.mouseEnabledOverride());
        assertFalse(options.altScreenEnabled());
        assertFalse(options.colorEnabled());
        assertEquals(Boolean.TRUE, options.readModeOverride());
        assertEquals(UiStyles.Theme.LIGHT, options.themeOverride());
    }

    @Test
    @ResourceLock(Resources.LOCALE)
    void themeParsingAndCliFlagsIgnoreTheDefaultLocale() {
        Locale original = Locale.getDefault();
        Locale originalDisplay = Locale.getDefault(Locale.Category.DISPLAY);
        Locale originalFormat = Locale.getDefault(Locale.Category.FORMAT);
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            for (UiStyles.Theme theme : UiStyles.Theme.values()) {
                assertEquals(theme, UiStyles.Theme.parse(" " + theme.name() + " "));
                assertEquals(theme, TuiCliOptions.parse(new String[] {"--theme=" + theme.name()}).themeOverride());
            }
            assertEquals(DocumentOpenMode.WINDOWS_1251,
                TuiCliOptions.parse(new String[] {"--open-as=WINDOWS-1251", "file.txt"}).openMode());
            assertEquals(DocumentOpenMode.KOI8_R,
                TuiCliOptions.parse(new String[] {"--open-as", " KOI8-R ", "file.txt"}).openMode());
        } finally {
            Locale.setDefault(original);
            Locale.setDefault(Locale.Category.DISPLAY, originalDisplay);
            Locale.setDefault(Locale.Category.FORMAT, originalFormat);
        }
    }

    @Test
    void profileFileEnablesProfilingInBothForms() {
        TuiCliOptions separate = TuiCliOptions.parse(new String[] {"--profile-file", "first.log"});
        TuiCliOptions inline = TuiCliOptions.parse(new String[] {"--profile-file=second.log"});

        assertTrue(separate.profileEnabled());
        assertEquals(Path.of("first.log"), separate.profileFile());
        assertTrue(inline.profileEnabled());
        assertEquals(Path.of("second.log"), inline.profileFile());
    }

    @Test
    void parsesHelpAliases() {
        assertTrue(TuiCliOptions.parse(new String[] {"--help"}).showHelp());
        assertTrue(TuiCliOptions.parse(new String[] {"-h"}).showHelp());
    }

    @Test
    void versionIsAnOptionOnlyOutsideValuesAndTheLiteralFilename() {
        assertTrue(TuiCliOptions.parse(new String[] {"--version"}).showVersion());
        assertTrue(TuiCliOptions.parse(new String[] {"--open-as-utf16le", "--version"}).showVersion());
        TuiCliOptions literal = TuiCliOptions.parse(new String[] {"--", "--version"});
        assertEquals(Path.of("--version"), literal.file());
        assertFalse(literal.showVersion());
        TuiCliOptions profile = TuiCliOptions.parse(new String[] {"--profile-file", "--version"});
        assertEquals(Path.of("--version"), profile.profileFile());
        assertFalse(profile.showVersion());
    }

    @Test
    void rejectsMissingProfileFileValue() {
        IllegalArgumentException missing = assertThrows(
            IllegalArgumentException.class,
            () -> TuiCliOptions.parse(new String[] {"--profile-file"})
        );

        assertEquals("Missing value after --profile-file", missing.getMessage());
    }

    @Test
    void preservesEmptyInlineProfilePathBehavior() {
        TuiCliOptions options = TuiCliOptions.parse(new String[] {"--profile-file="});

        assertTrue(options.profileEnabled());
        assertEquals(Path.of(""), options.profileFile());
    }

    @ParameterizedTest
    @ValueSource(strings = {"--", "-h", "--help", "--theme=blue", "--read", "--ui=gui", "--profile-file=x", "--file-uri=x"})
    void treatsArgumentsAfterTheSeparatorAsLiteralFilePaths(String filename) {
        TuiCliOptions options = TuiCliOptions.parse(new String[] {"--", filename});

        assertEquals(Path.of(filename), options.file());
        assertFalse(options.showHelp());
        assertNull(options.themeOverride());
        assertNull(options.readModeOverride());
        assertFalse(options.profileEnabled());
    }

    @Test
    void separatorPreservesEarlierOptionsAndCanHaveNoFileAfterIt() {
        TuiCliOptions options = TuiCliOptions.parse(new String[] {"--read", "--theme=light", "--"});

        assertNull(options.file());
        assertEquals(Boolean.TRUE, options.readModeOverride());
        assertEquals(UiStyles.Theme.LIGHT, options.themeOverride());
        assertEquals(Path.of("notes.txt"), TuiCliOptions.parse(new String[] {"notes.txt", "--"}).file());
    }

    @Test
    void separatorAsAProfileValueDoesNotEndOptionParsing() {
        TuiCliOptions options = TuiCliOptions.parse(new String[] {"--profile-file", "--", "--read", "--", "-h"});

        assertEquals(Path.of("--"), options.profileFile());
        assertTrue(options.profileEnabled());
        assertEquals(Boolean.TRUE, options.readModeOverride());
        assertEquals(Path.of("-h"), options.file());
        assertFalse(options.showHelp());
    }

    @Test
    void stillRejectsMoreThanOneFileAcrossTheSeparator() {
        for (String[] args : new String[][] {
            {"--", "first", "second"}, {"first", "--", "-h"}, {"--", "--", "--help"}
        }) {
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> TuiCliOptions.parse(args));
            assertEquals("Only one file path is supported.", failure.getMessage());
        }
    }

    @Test
    void nullArgumentsRemainInvalidBeforeAndAfterTheSeparatorAndAsOptionValues() {
        assertThrows(IllegalArgumentException.class, () -> TuiCliOptions.parse(null));
        for (String[] args : new String[][] {{null}, {"--", null}, {"--profile-file", null}, {"--open-as", null}}) {
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> TuiCliOptions.parse(args));
            assertEquals("Command-line arguments must not be null", failure.getMessage());
        }
    }

    @Test
    void rejectsUnknownOptionAndMultipleFiles() {
        assertThrows(
            IllegalArgumentException.class,
            () -> TuiCliOptions.parse(new String[] {"--unknown"})
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> TuiCliOptions.parse(new String[] {"one.txt", "two.txt"})
        );
    }

    @Test
    void acceptsExplicitFileUrisInBothFormsAndKeepsOrdinaryFileColonNamesLiteral() {
        Path expected = Path.of("notes #50% + 中😀.txt").toAbsolutePath();
        String uri = expected.toUri().toASCIIString();
        assertEquals(expected, TuiCliOptions.parse(new String[] {"--file-uri", uri}).file());
        assertEquals(expected, TuiCliOptions.parse(new String[] {"--file-uri=" + uri}).file());
        assertEquals(Path.of("file:/literal.txt"), TuiCliOptions.parse(new String[] {"file:/literal.txt"}).file());
    }

    @Test
    void fileUriAndPositionalFileCannotSelectTwoDocuments() {
        String uri = Path.of("first.txt").toAbsolutePath().toUri().toASCIIString();
        for (String[] args : new String[][] {
            {"--file-uri", uri, "second.txt"}, {"second.txt", "--file-uri=" + uri},
            {"--file-uri=" + uri, "--", "second.txt"}, {"--file-uri=" + uri, "--file-uri=" + uri}
        }) {
            var failure = assertThrows(IllegalArgumentException.class, () -> TuiCliOptions.parse(args));
            assertEquals("Only one file path is supported.", failure.getMessage());
        }
    }

    @Test
    void ambiguousDecodedPathsAreRejectedBeforeTheyCanSelectAFileOrLog() {
        for (String[] args : new String[][] {
            {"legacy-�.txt"}, {"--", "legacy-�.txt"},
            {"--profile-file", "legacy-�.log"}, {"--profile-file=legacy-�.log"}
        }) {
            var failure = assertThrows(IllegalArgumentException.class, () -> TuiCliOptions.parse(args));
            assertTrue(failure.getMessage().contains("U+FFFD"), failure.getMessage());
        }
        String literalReplacementUri = Path.of("literal-�.txt").toAbsolutePath().toUri().toASCIIString();
        assertEquals(Path.of(URI.create(literalReplacementUri)),
            TuiCliOptions.parse(new String[] {"--file-uri=" + literalReplacementUri}).file());
    }

    @Test
    void malformedMissingAndNonAsciiFileUrisAreUsageErrors() {
        assertEquals("Missing value after --file-uri", assertThrows(IllegalArgumentException.class,
            () -> TuiCliOptions.parse(new String[] {"--file-uri"})).getMessage());
        assertThrows(IllegalArgumentException.class, () -> TuiCliOptions.parse(new String[] {"--file-uri", null}));
        for (String uri : new String[] {"", "notes.txt", "file:relative.txt", "https://example.org/file",
            "file:///bad%zz", "file:///bad%00", "file:///bad path", "file:///tmp/a?b", "file:///tmp/a#b", "file:///tmp/�"}) {
            assertThrows(IllegalArgumentException.class, () -> TuiCliOptions.parse(new String[] {"--file-uri=" + uri}), uri);
        }
    }
}
