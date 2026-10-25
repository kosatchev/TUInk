package dev.tuink.storage.jvm;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tuink.core.*;
import dev.tuink.editor.language.LanguageRegistry;
import dev.tuink.storage.jvm.JvmDocumentStorage;
import dev.tuink.storage.jvm.spi.LinuxXattrAccess;
import dev.tuink.unicode.GraphemeText;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.ServiceConfigurationError;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

@EnabledOnOs(OS.LINUX)
public class LinuxXattrProviderIsolationTest {
    @TempDir Path directory;

    private enum Configuration { MISSING, BROKEN, MULTIPLE }

    @ParameterizedTest
    @EnumSource(Configuration.class)
    void aMissingOrInvalidProviderCannotSilentlyRemoveAFileAcl(Configuration configuration) throws Exception {
        Path target = Files.writeString(directory.resolve("document.txt"), "original");
        LinuxAclFixtures.setAccessAcl(target, LinuxAclFixtures.namedAcl(target, 4, 0));
        byte[] acl = LinuxAclFixtures.accessAcl(target);
        int mode = (int) Files.getAttribute(target, "unix:mode");
        Path servicesRoot = Files.createDirectory(directory.resolve("service-fixture"));
        if (configuration != Configuration.MISSING) {
            Path descriptor = servicesRoot.resolve("META-INF/services/" + LinuxXattrAccess.class.getName());
            Files.createDirectories(descriptor.getParent());
            Files.writeString(descriptor, configuration == Configuration.BROKEN ? "missing.Provider\n"
                : FirstProvider.class.getName() + "\n" + SecondProvider.class.getName() + "\n");
        }
        String classpath = String.join(File.pathSeparator, location(getClass()), location(TextModel.class),
            location(GraphemeText.class), location(LanguageRegistry.class),
            location(JvmDocumentStorage.class), servicesRoot.toString());
        Process process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "-Xmx64m", "-Duser.home=" + directory.resolve("home"), "-cp", classpath,
            IsolationProbe.class.getName(), target.toString(), configuration.name()).redirectErrorStream(true).start();
        try {
            assertTrue(process.waitFor(15, TimeUnit.SECONDS), "provider isolation probe exceeded its deadline");
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertEquals(0, process.exitValue(), output);
        } finally {
            process.destroyForcibly();
        }
        assertEquals("original", Files.readString(target));
        assertArrayEquals(acl, LinuxAclFixtures.accessAcl(target));
        assertEquals(mode, Files.getAttribute(target, "unix:mode"));
        try (var children = Files.list(directory)) {
            assertTrue(children.noneMatch(path -> path.getFileName().toString().endsWith(".tmp")));
        }
    }

    private static String location(Class<?> type) throws Exception {
        return Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
    }

    public static final class IsolationProbe {
        public static void main(String[] args) throws Exception {
            // Include the JVM implementation classes, but only this fixture's
            // service descriptors: discovery must fail closed for all three cases.
            TextModel model = new TextModel(new SimpleLineBuffer());
            EditorState state = new EditorState();
            EditorEngine engine = new EditorEngine(model, state, new LogicalEditorTextLayout(model));
            engine.setGutterDiffMode(GutterDiffMode.DISABLED);
            engine.setAutoPairsEnabled(false);
            engine.setAutoIndentEnabled(false);
            engine.setAutoListEnabled(false);
            engine.openExisting(JvmDocumentStorage.resource(Path.of(args[0])));
            engine.insertInput("edited ");
            engine.insertNewline();
            require(engine.undo(), "setup did not retain an undo entry");
            var lines = model.snapshotLines();
            long revision = model.revision();
            var metadata = model.fileMetadata();
            var baseline = JvmDocumentStorage.baselineOf(model.fileBaseline());
            var file = JvmDocumentStorage.pathOf(state.getCurrentResource());
            var disposition = state.getSaveDisposition();
            try {
                engine.save();
                throw new AssertionError("Saving without a usable ACL provider unexpectedly succeeded");
            } catch (IOException expected) {
                require(expected.getMessage().contains("storage provider"), "missing actionable provider failure");
                if (args[1].equals(Configuration.BROKEN.name())) {
                    require(expected.getCause() instanceof ServiceConfigurationError,
                        "a broken service descriptor was not reported as the provider failure");
                }
            }
            require(lines.equals(model.snapshotLines()) && revision == model.revision(), "failed save changed text");
            require(metadata.equals(model.fileMetadata()) && baseline.equals(JvmDocumentStorage.baselineOf(model.fileBaseline())),
                "failed save changed the saved metadata or baseline");
            require(state.isDirty() && file.equals(JvmDocumentStorage.pathOf(state.getCurrentResource())) && disposition == state.getSaveDisposition(),
                "failed save published a savepoint or path");
            require(engine.redo() && engine.undo(), "failed save lost redo history");
            require(lines.equals(model.snapshotLines()), "redo/undo no longer restores the same draft");
            require(engine.undo() && List.of("original").equals(model.snapshotLines()), "failed save lost undo history");
        }

        private static void require(boolean condition, String message) {
            if (!condition) throw new AssertionError(message);
        }
    }

    public static final class FirstProvider implements LinuxXattrAccess {
        public FirstProvider() {}
        public int get(byte[] path, byte[] name, byte[] value) { throw new AssertionError("ambiguous provider used"); }
        public int set(byte[] path, byte[] name, byte[] value) { throw new AssertionError("ambiguous provider used"); }
        public int remove(byte[] path, byte[] name) { throw new AssertionError("ambiguous provider used"); }
    }

    public static final class SecondProvider implements LinuxXattrAccess {
        public SecondProvider() {}
        public int get(byte[] path, byte[] name, byte[] value) { throw new AssertionError("ambiguous provider used"); }
        public int set(byte[] path, byte[] name, byte[] value) { throw new AssertionError("ambiguous provider used"); }
        public int remove(byte[] path, byte[] name) { throw new AssertionError("ambiguous provider used"); }
    }
}
