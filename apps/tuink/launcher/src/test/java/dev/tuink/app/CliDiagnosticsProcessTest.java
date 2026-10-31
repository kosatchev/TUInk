package dev.tuink.app;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import dev.tuink.tui.AppVersion;
import java.io.File;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CliDiagnosticsProcessTest {
    private static final List<String> ENTRYPOINTS = List.of(
        "dev.tuink.app.App", "dev.tuink.tui.TerminalEditorApp");

    @TempDir Path directory;

    @Test
    void bothEntrypointsReportVersionWithoutOpeningATerminalOrReadingTheProfile() throws Exception {
        Path profile = directory.resolve("existing-profile.log");
        byte[] original = "keep this profile\n".getBytes(StandardCharsets.UTF_8);
        Files.write(profile, original);
        Path missing = directory.resolve("missing-document.txt");

        for (String entrypoint : ENTRYPOINTS) {
            Result result = runWithConfig(entrypoint, "config-�", "--version",
                "--profile-file", profile.toString(), missing.toString());

            assertEquals(0, result.exitCode(), result.error());
            assertEquals("TUInk " + AppVersion.current() + System.lineSeparator(), result.output());
            assertEquals("", result.error());
            assertArrayEquals(original, Files.readAllBytes(profile));
            assertFalse(Files.exists(missing));
        }
    }

    @Test
    void bothEntrypointsReportFatalProfileFailuresWithoutEmittingFilenameControls() throws Exception {
        assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"),
            "This fixture requires POSIX filenames containing terminal controls");
        Path profile = directory.resolve("profile-кириллица-😀-\u001B]0;changed\u0007-\u009B31m-\r\nforged-\u202E.log");
        byte[] original = "existing profile must survive\n".getBytes(StandardCharsets.UTF_8);
        Files.write(profile, original);

        for (String entrypoint : ENTRYPOINTS) {
            Result result = run(entrypoint, "--profile-file", profile.toString());

            assertEquals(1, result.exitCode(), result.error());
            assertEquals("", result.output());
            assertTrue(result.error().contains("java.nio.file.FileAlreadyExistsException"), result.error());
            assertTrue(result.error().contains("Profile log must be a new file"), result.error());
            assertTrue(result.error().contains(
                "profile-кириллица-😀-\\u001B]0;changed\\u0007-\\u009B31m-\\r\\nforged-\\u202E.log"),
                result.error());
            assertTrue(result.error().contains("dev.tuink.tui.ProfileLog.open"), result.error());
            assertSafeOutput(result.error());
            assertArrayEquals(original, Files.readAllBytes(profile));
        }
    }

    @Test
    void bothEntrypointsKeepControlBearingFilenamesLiteralAfterTheOptionSeparator() throws Exception {
        assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"),
            "This fixture requires POSIX path syntax accepting terminal controls");
        String filename = "--ui=\u001B]0;changed\u0007-кириллица-😀.txt";
        for (String entrypoint : ENTRYPOINTS) {
            Result result = run(entrypoint, "--help", "--", filename);

            assertEquals(0, result.exitCode(), result.error());
            assertTrue(result.output().startsWith("Usage: tuink"), result.output());
            assertEquals("", result.error());
            assertSafeOutput(result.output());
            assertFalse(Files.exists(directory.resolve(filename)));
        }
    }

    private Result run(String entrypoint, String... arguments) throws Exception {
        return runWithConfig(entrypoint, "config", arguments);
    }

    private Result runWithConfig(String entrypoint, String configName, String... arguments) throws Exception {
        Path runDirectory = Files.createTempDirectory(directory, "cli-");
        Path home = Files.createDirectory(runDirectory.resolve("home"));
        Path config = Files.createDirectory(runDirectory.resolve(configName));
        Path output = runDirectory.resolve("stdout.txt");
        Path error = runDirectory.resolve("stderr.txt");
        String executable = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
        List<String> command = new ArrayList<>(List.of(
            Path.of(System.getProperty("java.home"), "bin", executable).toString(),
            "-Xmx128m", "-Dfile.encoding=UTF-8", "-Duser.home=" + home,
            "--enable-native-access=ALL-UNNAMED", "-cp", runtimeClasspath(), entrypoint));
        command.addAll(Arrays.asList(arguments));
        ProcessBuilder builder = new ProcessBuilder(command)
            .directory(directory.toFile()).redirectOutput(output.toFile()).redirectError(error.toFile());
        builder.environment().put("XDG_CONFIG_HOME", config.toString());
        for (String variable : List.of("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS")) {
            builder.environment().remove(variable);
        }
        Process child = builder.start();
        child.getOutputStream().close();
        try {
            assertTrue(child.waitFor(20, TimeUnit.SECONDS), "CLI did not exit: " + entrypoint);
            return new Result(child.exitValue(), Files.readString(output), Files.readString(error));
        } finally {
            child.destroyForcibly();
        }
    }

    private String runtimeClasspath() throws Exception {
        LinkedHashSet<String> entries = new LinkedHashSet<>();
        for (String entry : System.getProperty("java.class.path").split(File.pathSeparator)) {
            entries.add(Path.of(entry).toAbsolutePath().toString());
        }
        // Gradle loads the test runtime through its own URL class loader; the
        // java.class.path property alone can contain only the worker bootstrap.
        for (ClassLoader loader = getClass().getClassLoader(); loader != null; loader = loader.getParent()) {
            if (loader instanceof URLClassLoader urls) {
                for (var url : urls.getURLs()) {
                    if (url.getProtocol().equals("file")) entries.add(Path.of(url.toURI()).toString());
                }
            }
        }
        return String.join(File.pathSeparator, entries);
    }

    private static void assertSafeOutput(String output) {
        for (int index = 0; index < output.length(); index++) {
            char character = output.charAt(index);
            if (character == '\n') continue;
            if (character == '\r' && index + 1 < output.length() && output.charAt(index + 1) == '\n') continue;
            assertFalse(Character.isISOControl(character), "Raw control in CLI output: " + (int) character);
            assertFalse(character == '\u202E', "Raw bidi override in CLI output");
        }
    }

    private record Result(int exitCode, String output, String error) {}
}
