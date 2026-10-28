package dev.tuink.storage.jvm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import dev.tuink.editor.storage.DocumentLoad;
import dev.tuink.editor.storage.DocumentOpenMode;
import dev.tuink.editor.storage.ResourceBinding;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JvmFileAdvisoryTest {
    @TempDir Path directory;

    @Test
    void hardlinkAliasesMatchButEqualFileContentsDoNotDefineIdentity() throws Exception {
        assumePosix();
        Path original = Files.writeString(directory.resolve("original.txt"), "same bytes");
        Path alias = Files.createLink(directory.resolve("alias.txt"), original);
        Path unrelated = Files.writeString(directory.resolve("unrelated.txt"), "same bytes");
        ResourceBinding binding = loadBinding(original, false);

        assertTrue(JvmFileAdvisory.matchesOpenResource(binding, alias));
        assertFalse(JvmFileAdvisory.matchesOpenResource(binding, unrelated));
        assertFalse(JvmFileAdvisory.matchesOpenResource(binding, directory.getRoot()));
        assertFalse(JvmFileAdvisory.matchesOpenResource(ResourceBinding.unbound(), original));
    }

    @Test
    void retargetingTheOpeningSymlinkDoesNotTransferItsLoadedResource() throws Exception {
        assumePosix();
        Path original = Files.writeString(directory.resolve("original.txt"), "original");
        Path replacement = Files.writeString(directory.resolve("replacement.txt"), "replacement");
        Path alias = Files.createSymbolicLink(directory.resolve("alias.txt"), original.getFileName());
        ResourceBinding binding = loadBinding(alias, false);
        Files.delete(alias);
        Files.createSymbolicLink(alias, replacement.getFileName());

        assertFalse(JvmFileAdvisory.matchesOpenResource(binding, alias));
        assertFalse(JvmFileAdvisory.matchesOpenResource(binding, replacement));
        assertTrue(JvmFileAdvisory.matchesOpenResource(binding, original));
    }

    @Test
    void namedMissingDocumentsCanBeReusedAndBrokenLinksOccupySuggestedNames() throws Exception {
        Path missing = directory.resolve("draft.txt");
        ResourceBinding binding = loadBinding(missing, true);

        assertTrue(JvmFileAdvisory.matchesOpenResource(binding, missing));
        assertFalse(JvmFileAdvisory.exists(missing));
        assertNull(JvmFileAdvisory.inspectSaveTarget(missing));
        assumePosix();
        Path broken = Files.createSymbolicLink(directory.resolve("broken.txt"), missing.getFileName());
        assertTrue(JvmFileAdvisory.exists(broken));
        assertEquals(JvmFileAdvisory.SaveIssueKind.UNRESOLVED_PATH,
            JvmFileAdvisory.inspectSaveTarget(broken).kind());
    }

    @Test
    void parentSymlinksResolveBeforeDotSegmentsInAliasAndValidation() throws Exception {
        assumePosix();
        Path deeper = Files.createDirectories(directory.resolve("nested/deeper"));
        Path original = Files.writeString(deeper.getParent().resolve("document.txt"), "original");
        Path alias = Files.createSymbolicLink(directory.resolve("alias"), directory.relativize(deeper));
        Path requested = alias.resolve("../document.txt");

        assertTrue(JvmFileAdvisory.matchesOpenResource(loadBinding(original, false), requested));
        assertNull(JvmFileAdvisory.inspectSaveTarget(requested));
        assertEquals(JvmFileAdvisory.SaveIssueKind.TARGET_DIRECTORY,
            JvmFileAdvisory.inspectSaveTarget(directory.resolve(".")).kind());
        assertEquals(JvmFileAdvisory.SaveIssueKind.UNRESOLVED_PATH,
            JvmFileAdvisory.inspectSaveTarget(directory.resolve("absent/document.txt")).kind());
    }

    private ResourceBinding loadBinding(Path path, boolean allowMissing) throws Exception {
        var resource = JvmDocumentStorage.resource(path);
        DocumentLoad loaded = resource.storage().load(resource, DocumentOpenMode.DETECT, allowMissing);
        return new ResourceBinding(loaded.resource(), loaded.disposition(), loaded.baseline());
    }

    private void assumePosix() throws Exception {
        assumeTrue(Files.getFileStore(directory).supportsFileAttributeView("posix"));
    }
}
