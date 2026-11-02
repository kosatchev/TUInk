package dev.tuink.editor.storage;

import static org.junit.jupiter.api.Assertions.*;

import dev.tuink.editor.document.ImmutableText;
import java.io.IOException;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class DocumentSavePlanTest {
    private enum Fault { NONE, WRONG_ID, FOREIGN_PROVIDER, BAD_LINE_COUNT, NULL_RECEIPT, RECEIPT_ID_OOM, WRITE_OOM }

    @ParameterizedTest
    @EnumSource(value = Fault.class, names = "NONE", mode = EnumSource.Mode.EXCLUDE)
    void aPossibleCommitWithAnInvalidReceiptIsAlwaysUnknownAndCannotBeRetried(Fault fault) throws Exception {
        var storage = new Storage(fault);
        var request = request(storage);
        var write = new DocumentSavePlan(request, ResourceBinding.unbound()).prepare(new SaveAuthorization.Current());
        var failure = assertThrows(StorageWriteException.class, write::write);
        assertEquals(StorageWriteException.Outcome.UNKNOWN, failure.outcome());
        assertEquals(request.resource(), failure.resource());
        assertTrue(storage.committed);
        assertFalse(write.produced(null));
        assertThrows(IllegalStateException.class, write::write);
        assertEquals(1, storage.writes);
    }

    @Test
    void matchingReceiptHasOneWriteAndIdentityBasedProvenance() throws Exception {
        var storage = new Storage(Fault.NONE);
        var write = new DocumentSavePlan(request(storage), ResourceBinding.unbound()).prepare(new SaveAuthorization.Current());
        var receipt = write.write();
        assertTrue(write.produced(receipt));
        var copy = new SavedDocument(receipt.resource(), receipt.metadata(), receipt.baseline(), receipt.publicationKind());
        assertFalse(write.produced(copy), "an equal reconstructed receipt is not the receipt returned by this write");
        assertThrows(IllegalStateException.class, write::write);
        assertEquals(1, storage.writes);
    }

    @Test
    void cancelledWriteAndForeignInspectionCannotReachPublication() throws Exception {
        var storage = new Storage(Fault.NONE);
        var plan = new DocumentSavePlan(request(storage), ResourceBinding.unbound());
        var operation = new StorageOperation(StorageOperation.Phase.STAGING);
        assertTrue(operation.requestCancellation());
        assertThrows(IOException.class, () -> plan.prepare(new SaveAuthorization.Current()).write(operation));
        assertEquals(0, storage.writes);
        storage.foreignInspection = true;
        assertThrows(IOException.class, () -> plan.inspect(StorageOperation.untracked()));
        assertEquals(0, storage.writes);
    }

    private static DocumentSave request(Storage storage) {
        return new DocumentSave(storage.resource("expected"), List.of(ImmutableText.of("source  ")),
            DocumentFileMetadata.newDocument(1), null, SaveDisposition.CREATE_NEW);
    }

    private record Id(String provider, String name) implements ResourceId {}
    private record Version(ResourceId resourceId) implements ResourceVersion {}
    private record Resource(String name, Storage owner) implements DocumentResource {
        @Override public ResourceId id() { return new Id("test:" + System.identityHashCode(owner), name); }
        @Override public String displayName() { return name; }
        @Override public String nameHint() { return name; }
        @Override public DocumentStorage storage() { return owner; }
    }
    private record Target(DocumentResource requestedResource, DocumentResource physicalResource) implements SaveTarget {
        @Override public boolean exists() { return false; }
        @Override public boolean currentTarget() { return false; }
        @Override public Set<StorageCapability> capabilities() { return Set.of(); }
    }

    private static final class Storage implements DocumentStorage {
        final Fault fault;
        int writes;
        boolean committed;
        boolean foreignInspection;
        Storage(Fault fault) { this.fault = fault; }
        Resource resource(String name) { return new Resource(name, this); }
        @Override public DocumentLoad load(DocumentResource resource, DocumentOpenMode mode, boolean allowMissing) {
            throw new AssertionError("save plans never load a resource");
        }
        @Override public SaveTarget inspect(DocumentResource resource, ResourceBinding current) {
            DocumentResource target = foreignInspection ? new Storage(Fault.NONE).resource("foreign") : resource;
            return new Target(target, target);
        }
        @Override public boolean isCurrentTarget(DocumentResource resource, ResourceBinding current) { return false; }
        @Override public SavedDocument write(DocumentSave request, SaveAuthorization authorization) {
            writes++;
            committed = true;
            if (fault == Fault.WRITE_OOM) throw new OutOfMemoryError("provider committed before allocating receipt");
            if (fault == Fault.NULL_RECEIPT) return null;
            DocumentResource receiptResource = switch (fault) {
                case WRONG_ID -> resource("different");
                case FOREIGN_PROVIDER -> new Storage(Fault.NONE).resource("expected");
                case RECEIPT_ID_OOM -> new DocumentResource() {
                    @Override public ResourceId id() { throw new OutOfMemoryError("receipt identity allocation"); }
                    @Override public String displayName() { return "expected"; }
                    @Override public String nameHint() { return "expected"; }
                    @Override public DocumentStorage storage() { return Storage.this; }
                };
                default -> request.resource();
            };
            var metadata = fault == Fault.BAD_LINE_COUNT ? DocumentFileMetadata.newDocument(2) : request.metadata();
            return new SavedDocument(receiptResource, metadata, new Version(request.resource().id()), PublicationKind.EXCLUSIVE_CREATE);
        }
    }
}
