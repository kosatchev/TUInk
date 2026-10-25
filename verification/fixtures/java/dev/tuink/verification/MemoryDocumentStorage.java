package dev.tuink.verification;

import dev.tuink.core.DocumentCodec;
import dev.tuink.editor.storage.*;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Shared fixture for the public storage protocol, with no filesystem or JVM provider. */
public final class MemoryDocumentStorage implements DocumentStorage {
    public enum WriteFailure { NONE, BEFORE_ENCODING, AFTER_ENCODING, AFTER_COMMIT }

    private final String namespace = "memory:" + UUID.randomUUID();
    private Map<String, Entry> entries = new HashMap<>();
    private long nextGeneration;
    private int calls;
    private boolean writable = true;
    private WriteFailure failure = WriteFailure.NONE;

    public DocumentResource resource(String name) {
        return new Handle(Objects.requireNonNull(name), this, new Id(namespace, name));
    }

    public synchronized void put(String name, byte[] bytes) {
        entries.put(name, new Entry(bytes.clone(), ++nextGeneration));
    }

    public synchronized byte[] bytes(String name) {
        Entry value = entries.get(name);
        return value == null ? null : value.bytes.clone();
    }

    public synchronized int calls() { return calls; }
    public synchronized void writable(boolean value) { writable = value; }
    public synchronized void failNextWrite(WriteFailure value) { failure = Objects.requireNonNull(value); }

    @Override
    public DocumentLoad load(DocumentResource resource, DocumentOpenMode mode, boolean allowMissing) throws IOException {
        Handle handle = handle(resource);
        byte[] bytes;
        synchronized (this) {
            calls++;
            Entry entry = entries.get(handle.name);
            if (entry == null) {
                if (!allowMissing) throw new IOException("Missing resource: " + handle.displayName());
                return new DocumentLoad(handle, List.of(""), DocumentFileMetadata.newDocument(1), null,
                    SaveDisposition.CREATE_NEW);
            }
            bytes = entry.bytes;
        }
        var decoded = DocumentCodec.read(new ByteArrayInputStream(bytes), mode,
            DocumentCodec.currentLoadLimits(), handle.displayName());
        return new DocumentLoad(handle, decoded.lines(), decoded.metadata(),
            new Version(handle.id(), decoded.fingerprint()), SaveDisposition.REPLACE_EXISTING);
    }

    @Override
    public synchronized SaveTarget inspect(DocumentResource resource, ResourceBinding current) throws IOException {
        Handle handle = handle(resource);
        calls++;
        Entry entry = entries.get(handle.name);
        return new Ticket(handle, entry == null ? -1L : entry.generation,
            isCurrent(handle, current));
    }

    @Override
    public synchronized boolean isCurrentTarget(DocumentResource resource, ResourceBinding current) throws IOException {
        calls++;
        return isCurrent(handle(resource), current);
    }

    private boolean isCurrent(Handle handle, ResourceBinding current) {
        return current.disposition() == SaveDisposition.REPLACE_EXISTING
            && current.resource() instanceof Handle owned && owned.owner == this && handle.id.equals(owned.id);
    }

    @Override
    public SavedDocument write(DocumentSave request, SaveAuthorization authorization) throws IOException {
        Handle target = handle(request.resource());
        Ticket approved = null;
        if (authorization instanceof SaveAuthorization.Approved approval) {
            if (!(approval.target() instanceof Ticket ticket) || ticket.resource.owner != this
                || !ticket.resource.equals(target)) throw new IOException("Foreign approval ticket");
            approved = ticket;
        } else if (!(authorization instanceof SaveAuthorization.Current)) {
            throw new IOException("Unknown save authorization");
        }
        WriteFailure requestedFailure;
        synchronized (this) {
            calls++;
            if (!writable) throw new IOException("Permission revoked");
            requestedFailure = failure;
            failure = WriteFailure.NONE;
        }
        if (requestedFailure == WriteFailure.BEFORE_ENCODING) throw new IOException("Encoding was not started");
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        var encoded = DocumentCodec.write(output, request.lines(), request.metadata());
        byte[] bytes = output.toByteArray();
        if (requestedFailure == WriteFailure.AFTER_ENCODING) throw new IOException("Staged write failed");
        var version = new Version(target.id(), encoded.fingerprint());
        var receipt = new SavedDocument(target, encoded.metadata(), version,
            request.disposition() == SaveDisposition.CREATE_NEW
                ? PublicationKind.EXCLUSIVE_CREATE : PublicationKind.ATOMIC_REPLACEMENT);
        var uncertain = requestedFailure == WriteFailure.AFTER_COMMIT
            ? new StorageWriteException("Publication completed without acknowledgement", null,
                StorageWriteException.Outcome.UNKNOWN, target) : null;
        synchronized (this) {
            if (!writable) throw new IOException("Permission revoked");
            Entry original = entries.get(target.name);
            if (approved != null && (original == null ? -1L : original.generation) != approved.generation) {
                throw new IOException("Approved resource changed");
            }
            if (request.disposition() == SaveDisposition.CREATE_NEW && original != null) {
                throw new IOException("Resource already exists");
            }
            boolean verify = authorization instanceof SaveAuthorization.Current || approved.currentTarget;
            if (verify && (request.disposition() == SaveDisposition.REPLACE_EXISTING || request.baseline() != null)) {
                if (!(request.baseline() instanceof Version baseline) || !baseline.id.equals(target.id)) {
                    throw new IOException("No verifiable loaded version");
                }
                if (original == null || !baseline.fingerprint.equals(fingerprint(original.bytes))) {
                    throw new IOException("Resource changed outside the editor");
                }
            }
            // Allocate the new map and entry before the single publication. A
            // real heap failure during HashMap resizing cannot partly commit.
            Map<String, Entry> replacement = new HashMap<>(entries);
            replacement.put(target.name, new Entry(bytes, nextGeneration + 1));
            entries = replacement;
            nextGeneration++;
            if (uncertain != null) throw uncertain;
            return receipt;
        }
    }

    private Handle handle(DocumentResource resource) throws IOException {
        if (!(resource instanceof Handle handle) || handle.owner != this) throw new IOException("Foreign resource");
        return handle;
    }

    private static DocumentCodec.Fingerprint fingerprint(byte[] bytes) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest(bytes);
            return new DocumentCodec.Fingerprint(java.util.HexFormat.of().formatHex(digest), bytes.length);
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }

    private record Id(String provider, String name) implements ResourceId {}
    private record Version(Id id, DocumentCodec.Fingerprint fingerprint) implements ResourceVersion {
        @Override public ResourceId resourceId() { return id; }
    }
    private record Entry(byte[] bytes, long generation) {}
    private record Handle(String name, MemoryDocumentStorage owner, Id id) implements DocumentResource {
        @Override public String displayName() { return "memory:" + name; }
        @Override public String nameHint() { return name.substring(name.lastIndexOf('/') + 1); }
        @Override public DocumentStorage storage() { return owner; }
    }
    private record Ticket(Handle resource, long generation, boolean currentTarget) implements SaveTarget {
        @Override public DocumentResource requestedResource() { return resource; }
        @Override public DocumentResource physicalResource() { return resource; }
        @Override public boolean exists() { return generation >= 0; }
        @Override public Set<StorageCapability> capabilities() {
            return Set.of(StorageCapability.READ, StorageCapability.EXCLUSIVE_CREATE, StorageCapability.REPLACE,
                StorageCapability.ATOMIC_REPLACE, StorageCapability.CONDITIONAL_WRITE);
        }
    }
}
