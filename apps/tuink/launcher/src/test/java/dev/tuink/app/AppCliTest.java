package dev.tuink.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.parallel.Resources.SYSTEM_ERR;
import static org.junit.jupiter.api.parallel.Resources.SYSTEM_OUT;

import dev.tuink.tui.AppVersion;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;

@ResourceLock(SYSTEM_OUT)
@ResourceLock(SYSTEM_ERR)
class AppCliTest {
    @Test
    void invalidFrontendAndUiArgumentsReturnUsageErrorsWithoutStackTraces() throws Exception {
        String[][] invalidArguments = {
            {"--unknown-option"},
            {"--theme=unknown"},
            {"first.txt", "second.txt"},
            {"--profile-file"},
            {"--file-uri"},
            {"--file-uri", "--ui=gui"},
            {"--file-uri=file:///bad%zz"},
            {"ambiguous-�.txt"},
            {"--", "ambiguous-�.txt"},
            {"--profile-file=ambiguous-�.log"},
            {"--ui=gui"},
            {"--ui"}
        };
        for (String[] arguments : invalidArguments) {
            Result result = run(arguments);

            assertEquals(2, result.exitCode(), String.join(" ", arguments));
            assertEquals("", result.output());
            assertFalse(result.error().isBlank());
            assertFalse(result.error().contains("Exception in thread"), result.error());
            assertFalse(result.error().contains("\tat "), result.error());
        }
    }

    @Test
    void usageDiagnosticsEscapeExternalControlsWithoutLosingUnicode() throws Exception {
        String payload = "кириллица café 😀\u001B]0;changed\u0007\u009B31m\r\nforged\u202E";
        String visiblePayload = "кириллица café 😀\\u001B]0;changed\\u0007\\u009B31m\\r\\nforged\\u202E";
        for (String argument : new String[] {
            "--unknown-" + payload,
            "--ui=" + payload,
            "--theme=" + payload,
            "path-" + payload + "\0.txt"
        }) {
            Result result = run(new String[] {argument});

            assertEquals(2, result.exitCode());
            assertEquals("", result.output());
            assertTrue(result.error().contains(visiblePayload), result.error());
            if (argument.indexOf('\0') >= 0) {
                assertTrue(result.error().contains("\\u0000.txt"), result.error());
            }
            assertFalse(result.error().contains("\u001B"), result.error());
            assertFalse(result.error().contains("\u0007"), result.error());
            assertFalse(result.error().contains("\u009B"), result.error());
            assertFalse(result.error().contains("\u202E"), result.error());
            assertFalse(result.error().contains("\nforged"), result.error());
            assertFalse(result.error().contains("Exception in thread"), result.error());
        }
    }

    @Test
    void helpReturnsSuccessAndWritesOnlyToStandardOutput() throws Exception {
        for (String[] arguments : new String[][] {{"--help"}, {"--ui=tui", "-h"}}) {
            Result result = run(arguments);

            assertEquals(0, result.exitCode());
            assertTrue(result.output().startsWith("Usage: tuink"));
            assertEquals("", result.error());
        }
    }

    @Test
    void versionReturnsSuccessAndWritesOnlyTheGeneratedVersionToStandardOutput() throws Exception {
        for (String[] arguments : new String[][] {
            {"--version"}, {"--ui=tui", "--version"}, {"--open-as-utf16le", "--version"}
        }) {
            Result result = run(arguments);

            assertEquals(0, result.exitCode());
            assertEquals("TUInk " + AppVersion.current() + System.lineSeparator(), result.output());
            assertEquals("", result.error());
        }
        Result help = run(new String[] {"--help", "--version"});
        assertTrue(help.output().startsWith("Usage: tuink"));
        assertTrue(help.output().contains("--version"));
        assertEquals(0, help.exitCode());
        assertEquals("", help.error());
    }

    @Test
    void endOfOptionsPreservesLiteralUiSelectorsAndHelpLookingFilenames() throws Exception {
        for (String filename : new String[] {"--ui=gui", "--ui", "--help", "-h", "--", "--version"}) {
            Result help = run(new String[] {"--help", "--", filename});
            assertEquals(0, help.exitCode());
            assertTrue(help.output().contains("[--] [file]"));
            assertEquals("", help.error());

            Result tooManyFiles = run(new String[] {"--", filename, "another-file"});
            assertEquals(2, tooManyFiles.exitCode());
            assertTrue(tooManyFiles.error().startsWith("Only one file path is supported."), tooManyFiles.error());
            assertEquals("", tooManyFiles.output());
        }
    }

    @Test
    void profileValuesLookingLikeUiSelectorsAndSeparatorsRemainValuesEndToEnd() throws Exception {
        for (String value : new String[] {"--ui=gui", "--ui", "--"}) {
            Result result = run(new String[] {"--profile-file", value, "--help"});
            assertEquals(0, result.exitCode(), value);
            assertTrue(result.output().startsWith("Usage: tuink"));
            assertEquals("", result.error());
        }
    }

    private static Result run(String[] arguments) throws Exception {
        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ByteArrayOutputStream error = new ByteArrayOutputStream();
        try (PrintStream out = new PrintStream(output, true, StandardCharsets.UTF_8);
             PrintStream err = new PrintStream(error, true, StandardCharsets.UTF_8)) {
            System.setOut(out);
            System.setErr(err);
            int exitCode = App.run(arguments);
            return new Result(exitCode, output.toString(StandardCharsets.UTF_8), error.toString(StandardCharsets.UTF_8));
        } finally {
            System.setOut(originalOut);
            System.setErr(originalErr);
        }
    }

    private record Result(int exitCode, String output, String error) {
    }
}
