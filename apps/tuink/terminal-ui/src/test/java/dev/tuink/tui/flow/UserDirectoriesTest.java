package dev.tuink.tui.flow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

class UserDirectoriesTest {
    @TempDir Path directory;

    @Test
    void unsetEmptyRelativeAndInvalidXdgValuesUseTheDefault() throws IOException {
        for (String value : new String[] {null, "", "relative", " ", "invalid\0path", "relative-\uFFFD"}) {
            assertEquals(directory.resolve(".config/tuink"),
                UserDirectories.configDirectory(value, directory.toString()));
        }
    }

    @Test
    void anAbsoluteXdgPathKeepsSpacesAndPhysicalParentResolution() throws IOException {
        Path configured = directory.resolve(" profile/link/../config ");
        assertEquals(configured.resolve("tuink"),
            UserDirectories.configDirectory(configured.toString(), directory.resolve("home").toString()));
    }

    @Test
    void anAmbiguousAbsoluteXdgPathIsRejectedWithoutFallingBack() {
        IOException error = assertThrows(IOException.class, () -> UserDirectories.configDirectory(
            directory.resolve("profile-\uFFFD").toString(), directory.resolve("home").toString()));
        assertTrue(error.getMessage().contains("XDG_CONFIG_HOME contains U+FFFD"));
        assertTrue(error.getMessage().contains("Set XDG_CONFIG_HOME"));
        assertFalse(Files.exists(directory.resolve("home/.config")));
    }

    @Test
    void anAmbiguousFallbackHomeIsRejectedOnlyWhenUsed() throws IOException {
        String ambiguousHome = directory.resolve("home-\uFFFD").toString();
        for (String value : new String[] {null, "", "relative", "invalid\0path"}) {
            IOException error = assertThrows(IOException.class,
                () -> UserDirectories.configDirectory(value, ambiguousHome));
            assertTrue(error.getMessage().contains("user.home contains U+FFFD"));
        }
        Path selected = directory.resolve("selected profile");
        assertEquals(selected.resolve("tuink"),
            UserDirectories.configDirectory(selected.toString(), ambiguousHome));
    }

    @Test
    void fallbackHomeRejectsMalformedUnicodeWithoutRejectingSupplementaryCharacters() throws IOException {
        for (String suffix : new String[] {"\uD800", "\uDC00"}) {
            IOException error = assertThrows(IOException.class, () -> UserDirectories.configDirectory(
                null, directory + "/home-" + suffix));
            assertTrue(error.getMessage().contains("unpaired Unicode surrogate"));
        }
        Path home = directory.resolve("home-\uD83D\uDC27");
        assertEquals(home.resolve(".config/tuink"), UserDirectories.configDirectory(null, home.toString()));
    }

    private Path runProbe(Path home, String configuredHome, String... arguments) throws Exception {
        var commandLine = new java.util.ArrayList<>(List.of(
            Path.of(System.getProperty("java.home"), "bin/java").toString(),
            "-Duser.home=" + home, "-cp", probeClasspath(), ProfileProbe.class.getName()));
        commandLine.addAll(List.of(arguments));
        ProcessBuilder command = new ProcessBuilder(commandLine);
        command.environment().put("XDG_CONFIG_HOME", configuredHome);
        return runProbe(command);
    }

    private Path runProbe(ProcessBuilder command) throws Exception {
        Path output = Files.createTempFile(directory, "profile-", ".log");
        Process child = command.redirectErrorStream(true).redirectOutput(output.toFile()).start();
        try {
            assertTrue(child.waitFor(15, TimeUnit.SECONDS), "Profile probe exceeded its deadline");
            assertEquals(0, child.exitValue(), Files.readString(output, StandardCharsets.UTF_8));
        } finally {
            child.destroyForcibly();
        }
        return output;
    }

    private static String probeClasspath() throws Exception {
        return String.join(File.pathSeparator,
            location(UserDirectoriesTest.class), location(UserDirectories.class), location(AppSettings.class),
            location(dev.tuink.core.RecoverySnapshot.class), location(dev.tuink.editor.document.DocumentId.class));
    }

    private static String location(Class<?> type) throws Exception {
        return Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
    }

    public static final class ProfileProbe {
        public static void main(String[] args) throws Exception {
            if ("reject".equals(args[0])) {
                rejects(() -> AppSettingsStore.path(), args[1]);
                rejects(() -> AppSettingsStore.loadSnapshot(), args[1]);
                rejects(() -> AppSettingsStore.save(AppSettings.defaults()), args[1]);
                rejects(() -> new RecoveryStore().close(), args[1]);
                System.out.println("All persistence entry points rejected the ambiguous profile");
                return;
            }
            Path expected = Path.of(args[0]);
            if (!AppSettingsStore.path().equals(expected.resolve("tuink.properties"))) {
                throw new AssertionError("Settings ignored the selected profile: " + AppSettingsStore.path());
            }
            AppSettingsStore.save(AppSettings.defaults());
            if (!AppSettingsStore.load().equals(AppSettings.defaults())) {
                throw new AssertionError("Selected profile settings did not round-trip");
            }
            try (RecoveryStore store = new RecoveryStore()) {
                if (!store.directory().equals(expected.resolve("recovery"))) {
                    throw new AssertionError("Recovery ignored the selected profile: " + store.directory());
                }
                store.save("profile-probe", new dev.tuink.core.RecoverySnapshot(
                    List.of("profile draft"), null, null, null, true, null, null, null, null, null));
                if (store.loadAll().entries().size() != 1) {
                    throw new AssertionError("Selected profile recovery did not round-trip");
                }
            }
        }

        private static void rejects(IoAction action, String source) throws IOException {
            try {
                action.run();
            } catch (IOException rejected) {
                if (rejected.getMessage().contains(source) && rejected.getMessage().contains("U+FFFD")) return;
                throw new AssertionError("Unexpected persistence error", rejected);
            }
            throw new AssertionError("Persistence accepted an ambiguous profile: " + source);
        }

        @FunctionalInterface
        private interface IoAction {
            void run() throws IOException;
        }
    }
}
