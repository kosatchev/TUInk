package dev.tuink.editor.storage;

import dev.tuink.editor.document.DocumentId;
import dev.tuink.editor.document.DocumentSnapshot;

import static org.junit.jupiter.api.Assertions.*;

import dev.tuink.editor.storage.BomKind;
import dev.tuink.editor.storage.DocumentFileMetadata;
import dev.tuink.editor.storage.DocumentResource;
import dev.tuink.editor.storage.DocumentStorage;
import dev.tuink.editor.storage.ResourceBinding;
import dev.tuink.editor.storage.ResourceId;
import dev.tuink.editor.storage.SaveDisposition;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

class FrozenDocumentSourceTest {
    @Test
    void envelopeSharesExactRawTextMixedSeparatorsAndFinalEmptyLine() {
        var text = new DocumentSnapshot(new DocumentId("raw"), 7,
            List.of("$\\int_0^1 x^2 \\,dx$  ", "```mermaid", "\tA-->B", ""));
        var metadata = new DocumentFileMetadata(StandardCharsets.UTF_16LE, BomKind.UTF_16_LE,
            "\n", List.of("\r\n", "\r", "\n"));
        var frozen = new FrozenDocumentSource(text, metadata, ResourceBinding.unbound());
        assertSame(text, frozen.text());
        assertSame(metadata, frozen.metadata());
        assertEquals(DocumentOpenMode.DETECT, frozen.reopenMode());
        assertSame(text.lineText(0), frozen.text().immutableLines().get(0));
        assertSame(text.immutableLines(), frozen.text().immutableLines());
        assertEquals("", frozen.text().line(3));
        assertEquals(List.of("\r\n", "\r", "\n"), frozen.metadata().lineSeparators());
        assertThrows(UnsupportedOperationException.class, () -> text.immutableLines().clear());
    }

    @Test
    void confirmedBindingChangeRetainsTheAcceptedTextAndFormatWithoutCallingAProvider() {
        var text = new DocumentSnapshot(new DocumentId("accepted"), 3, List.of("unsaved snapshot"));
        var metadata = DocumentFileMetadata.newDocument(1);
        var frozen = new FrozenDocumentSource(text, metadata, ResourceBinding.unbound(), DocumentOpenMode.CP866);
        DocumentResource resource = new DocumentResource() {
            @Override public ResourceId id() { return (() -> "frozen-test"); }
            @Override public String displayName() { return "saved.md"; }
            @Override public String nameHint() { return "saved.md"; }
            @Override public DocumentStorage storage() { throw new AssertionError("binding must not call storage"); }
        };
        var binding = new ResourceBinding(resource, SaveDisposition.CREATE_NEW, null);
        var updated = frozen.withBinding(binding);
        assertSame(text, updated.text());
        assertSame(metadata, updated.metadata());
        assertSame(binding, updated.binding());
        assertEquals(DocumentOpenMode.CP866, updated.reopenMode());
        assertSame(updated, updated.withBinding(binding));
        assertNull(frozen.binding().resource());
        var converted = updated.withBinding(binding, DocumentOpenMode.UTF_8);
        assertSame(text, converted.text());
        assertSame(metadata, converted.metadata());
        assertSame(binding, converted.binding());
        assertEquals(DocumentOpenMode.UTF_8, converted.reopenMode());
        assertEquals(DocumentOpenMode.CP866, updated.reopenMode());
        assertSame(converted, converted.withBinding(binding, DocumentOpenMode.UTF_8));
        assertThrows(NullPointerException.class, () -> converted.withBinding(binding, null));
    }

    @Test
    void mismatchedFormatAndNullFieldsAreRejectedBeforePublication() {
        var text = new DocumentSnapshot(new DocumentId("source"), 0, List.of("a", ""));
        assertThrows(IllegalArgumentException.class,
            () -> new FrozenDocumentSource(text, DocumentFileMetadata.newDocument(1), ResourceBinding.unbound()));
        assertThrows(NullPointerException.class,
            () -> new FrozenDocumentSource(null, DocumentFileMetadata.newDocument(1), ResourceBinding.unbound()));
        assertThrows(NullPointerException.class,
            () -> new FrozenDocumentSource(text, null, ResourceBinding.unbound()));
        assertThrows(NullPointerException.class,
            () -> new FrozenDocumentSource(text, DocumentFileMetadata.newDocument(2), null));
    }
}
