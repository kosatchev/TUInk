package dev.tuink.storage.jvm;

import static org.junit.jupiter.api.Assertions.*;

import dev.tuink.editor.storage.ResourceBinding;
import dev.tuink.editor.storage.SaveDisposition;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class JvmResourceBindingPathTest {
    @Test
    void capturedPhysicalBaselineTakesPrecedenceOverTheResourceAliasWithoutLookup() {
        Path alias = Path.of("alias", "document.txt").toAbsolutePath();
        Path physical = Path.of("physical", "document.txt").toAbsolutePath().normalize();
        var baseline = FileBaseline.fromBytes(physical, new byte[] {1, 2, 3});
        var binding = new ResourceBinding(JvmDocumentStorage.resource(alias),
            SaveDisposition.REPLACE_EXISTING, baseline);

        assertEquals(physical, JvmDocumentStorage.boundPhysicalPath(binding));
        assertEquals(alias, JvmDocumentStorage.pathOf(binding.resource()));
    }

    @Test
    void aNamedDocumentWithoutABaselineKeepsItsLexicalPath() {
        Path requested = Path.of("pending", "..", "new.txt").toAbsolutePath();
        var binding = new ResourceBinding(JvmDocumentStorage.resource(requested), SaveDisposition.CREATE_NEW, null);

        assertEquals(requested, JvmDocumentStorage.boundPhysicalPath(binding));
    }

    @Test
    void anUnnamedDocumentHasNoBoundPhysicalPath() {
        assertNull(JvmDocumentStorage.boundPhysicalPath(ResourceBinding.unbound()));
    }
}
