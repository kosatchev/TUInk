package dev.tuink.storage.jvm;

import dev.tuink.core.DocumentCodec;
import dev.tuink.editor.storage.DocumentFileMetadata;
import dev.tuink.editor.storage.DocumentLoad;
import dev.tuink.editor.storage.DocumentOpenMode;
import dev.tuink.editor.storage.DocumentResource;
import dev.tuink.editor.storage.DocumentSave;
import dev.tuink.editor.storage.DocumentStorage;
import dev.tuink.editor.storage.PublicationKind;
import dev.tuink.editor.storage.ResourceBinding;
import dev.tuink.editor.storage.ResourceId;
import dev.tuink.editor.storage.ResourceVersion;
import dev.tuink.editor.storage.SaveAuthorization;
import dev.tuink.editor.storage.SaveDisposition;
import dev.tuink.editor.storage.SaveTarget;
import dev.tuink.editor.storage.SavedDocument;
import dev.tuink.editor.storage.StorageCapability;
import dev.tuink.editor.storage.StorageOperation;
import dev.tuink.editor.storage.StorageWriteException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Filesystem transactions for JVM hosts. Handles and receipts retain native path identity. */
public final class JvmDocumentStorage implements DocumentStorage {
    static final int MAX_LOAD_BYTES = DocumentCodec.MAX_LOAD_BYTES;
    private static final JvmDocumentStorage INSTANCE = new JvmDocumentStorage(new PublicationFiles() {});
    private static final Set<PosixFilePermission> NEW_FILE_PERMISSIONS = Set.of(
        PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
        PosixFilePermission.GROUP_READ, PosixFilePermission.GROUP_WRITE,
        PosixFilePermission.OTHERS_READ, PosixFilePermission.OTHERS_WRITE);
    // Atomic replacement is attempted, but its portable fallback is deliberately
    // not advertised as an unconditional atomic-replacement capability.
    private static final Set<StorageCapability> CAPABILITIES = Set.of(StorageCapability.READ,
        StorageCapability.EXCLUSIVE_CREATE, StorageCapability.REPLACE);
    private final PublicationFiles publicationFiles;
    private final FileBaseline.InputOpener baselineInput;
    JvmDocumentStorage(PublicationFiles publicationFiles) {
        this(publicationFiles, Files::newInputStream);
    }

    JvmDocumentStorage(PublicationFiles publicationFiles, FileBaseline.InputOpener baselineInput) {
        this.publicationFiles = Objects.requireNonNull(publicationFiles, "publicationFiles");
        this.baselineInput = Objects.requireNonNull(baselineInput, "baselineInput");
    }

    /** Pure wrapping: no lookup, URI conversion or normalization of symlink/.. components. */
    public static DocumentResource resource(Path path) {
        return INSTANCE.fileResource(path);
    }

    /** JVM-host escape hatch for presentation and the existing recovery wire format. */
    public static Path pathOf(DocumentResource resource) {
        if (resource == null) return null;
        if (!(resource instanceof FileResource file)) {
            throw new IllegalArgumentException("Not a JVM file resource");
        }
        return file.path;
    }

    /** Pure host conversion for recovery serialization; a foreign version is never reinterpreted. */
    public static FileBaseline baselineOf(ResourceVersion version) {
        if (version == null) return null;
        if (version instanceof FileBaseline baseline) return baseline;
        throw new IllegalArgumentException("Not a JVM file version");
    }

    /**
     * Captured physical location for host filesystem operations. This performs
     * no lookup: even a now-missing alias retains the location of its baseline.
     * A named document without a byte baseline retains its lexical resource path.
     */
    public static Path boundPhysicalPath(ResourceBinding binding) {
        Objects.requireNonNull(binding, "binding");
        Path requested = pathOf(binding.resource());
        FileBaseline baseline = baselineOf(binding.version());
        return baseline == null ? requested : baseline.target();
    }

    /**
     * Maps a captured binding after the host has confirmed a filesystem move.
     * The original byte fingerprint is retained, including an existing external
     * conflict; observing the moved bytes must not silently authorize replacing
     * changes made outside the editor. This never writes or returns the old path.
     */
    public static ResourceBinding relocatedBinding(ResourceBinding original, Path destination) {
        Objects.requireNonNull(original, "original");
        Objects.requireNonNull(destination, "destination");
        if (!(original.resource() instanceof FileResource file)) {
            throw new IllegalArgumentException("Not a JVM file resource");
        }
        Path requested = destination.toAbsolutePath().normalize();
        FileBaseline baseline = baselineOf(original.version());
        ResourceVersion relocated = null;
        if (baseline != null) {
            Path target;
            try { target = requested.toRealPath(); }
            catch (IOException | SecurityException inaccessible) {
                // Publication has already happened. Retain the destination and
                // old fingerprint so later Save fails closed if access was lost.
                target = requested;
            }
            relocated = new FileBaseline(target, baseline.sha256(), baseline.byteSize());
        }
        return new ResourceBinding(file.storage.fileResource(requested), original.disposition(), relocated);
    }

    DocumentResource fileResource(Path path) {
        return new FileResource(this, Objects.requireNonNull(path, "path").toAbsolutePath());
    }

    static ResourceId idOf(Path path) { return new FileId(path); }

    private Path ownPath(DocumentResource resource) throws IOException {
        if (!(resource instanceof FileResource file) || file.storage != this) {
            throw new IOException("Resource belongs to another storage provider");
        }
        return file.path;
    }

    @Override public DocumentLoad load(DocumentResource resource, DocumentOpenMode mode, boolean allowMissing)
        throws IOException {
        Objects.requireNonNull(mode, "mode");
        Path requested = resolveDocumentPath(ownPath(resource));
        Path target;
        try {
            target = requested.toRealPath();
        } catch (NoSuchFileException missing) {
            if (!allowMissing || Files.isSymbolicLink(requested) || !Files.isDirectory(requested.getParent())) {
                throw missing;
            }
            return new DocumentLoad(fileResource(requested), List.of(""), DocumentFileMetadata.newDocument(1),
                null, SaveDisposition.CREATE_NEW);
        }
        BasicFileAttributes attributes = Files.readAttributes(target, BasicFileAttributes.class);
        if (!attributes.isRegularFile()) throw new IOException("Not a regular file: " + requested);
        DocumentCodec.LoadLimits limits = DocumentCodec.currentLoadLimits();
        DocumentCodec.requireLoadSize(attributes.size(), limits, target.toString());
        DocumentCodec.DecodedContent decoded;
        try (InputStream input = Files.newInputStream(target)) {
            decoded = DocumentCodec.read(input, mode, limits, target.toString());
        }
        FileBaseline baseline = new FileBaseline(target, decoded.fingerprint().sha256(), decoded.fingerprint().byteSize());
        return new DocumentLoad(fileResource(requested), decoded.lines(), decoded.metadata(), baseline,
            SaveDisposition.REPLACE_EXISTING);
    }

    @Override public SaveTarget inspect(DocumentResource resource, ResourceBinding current) throws IOException {
        return inspect(resource, current, StorageOperation.untracked());
    }

    @Override public SaveTarget inspect(DocumentResource resource, ResourceBinding current,
                                        StorageOperation operation) throws IOException {
        Path spelling = ownPath(resource);
        PreparedSaveTarget target = PreparedSaveTarget.inspect(spelling, operation);
        boolean ownsTarget = isCurrentTarget(spelling, target.requested(), current);
        return new FileSaveTarget(this, resource.id(), target, fileResource(target.requested()),
            fileResource(target.target()), ownsTarget);
    }

    @Override public boolean isCurrentTarget(DocumentResource resource, ResourceBinding current) throws IOException {
        Path requested = ownPath(resource);
        return isCurrentTarget(requested, resolveDocumentPath(requested), current);
    }

    private boolean isCurrentTarget(Path requested, Path resolved, ResourceBinding current) throws IOException {
        Objects.requireNonNull(current, "current");
        if (current.resource() == null || current.resource().storage() != this) return false;
        Path currentFile = ownPath(current.resource());
        FileBaseline baseline = fileBaseline(current.version());
        boolean ownsCurrentName = current.disposition() == SaveDisposition.REPLACE_EXISTING;
        if (ownsCurrentName && (requested.equals(currentFile) || resolved.equals(currentFile))) return true;
        if (baseline == null) return ownsCurrentName && isSameSaveTarget(resolved, currentFile);
        Path loadedTarget = baseline.target();
        if (resolved.equals(loadedTarget)) return true;
        // An opening alias that now points elsewhere cannot transfer ownership.
        return Files.exists(loadedTarget) && loadedTarget.equals(loadedTarget.toRealPath())
            && isSameSaveTarget(resolved, loadedTarget);
    }

    private static boolean isSameSaveTarget(Path requested, Path known) throws IOException {
        return requested.equals(known) || Files.exists(requested) && Files.exists(known)
            && Files.isSameFile(requested, known);
    }

    @Override public SavedDocument write(DocumentSave request, SaveAuthorization authorization) throws IOException {
        return write(request, authorization, StorageOperation.untracked());
    }

    @Override public SavedDocument write(DocumentSave request, SaveAuthorization authorization,
                                         StorageOperation operation) throws IOException {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(authorization, "authorization");
        Objects.requireNonNull(operation, "operation");
        operation.checkCancelled();
        Path spelling = ownPath(request.resource());
        FileSaveTarget approved = null;
        if (authorization instanceof SaveAuthorization.Approved approval) {
            if (!(approval.target() instanceof FileSaveTarget target) || target.storage != this
                || !target.originalId.equals(request.resource().id())) {
                throw new IOException("Save approval belongs to another resource or storage provider");
            }
            approved = target;
        }
        boolean replaceExternal = authorization instanceof SaveAuthorization.Approved approval && approval.replaceExternalChanges();
        boolean checkBaseline = approved == null || approved.currentTarget() && !replaceExternal;
        // Cross-provider Save As can carry an unrelated source baseline. Only an
        // owned-target write may use it, and then the provider must recognize it.
        FileBaseline baseline = checkBaseline ? fileBaseline(request.baseline()) : null;
        SaveDisposition disposition = approved != null && !approved.exists()
            ? SaveDisposition.CREATE_NEW : request.disposition();
        boolean verifyOriginal = checkBaseline && (disposition == SaveDisposition.REPLACE_EXISTING || baseline != null);
        Path tempFile = null;
        FileSaveGuard guard = null;
        Publication publication = new Publication();
        Throwable failure = null;
        DocumentResource savedResource = approved == null ? request.resource() : approved.requestedResource();
        StorageWriteException unconfirmedSave = new StorageWriteException("Saving may have changed the file: "
            + savedResource.displayName() + ". Verify its contents before retrying; the editor retains your draft.",
            null, StorageWriteException.Outcome.UNKNOWN, savedResource);
        try {
            operation.progress(StorageOperation.Phase.VERIFYING, 0L, -1L);
            if (approved != null) approved.target.beginWrite(operation);
            Path requested = approved == null ? resolveDocumentPath(spelling) : approved.target.requested();
            Path target;
            try {
                target = approved == null ? FileSaveGuard.canonicalTarget(requested) : approved.target.target();
            } catch (IOException inaccessible) {
                if (verifyOriginal) throw new IOException(FileBaseline.conflictMessage(requested), inaccessible);
                throw inaccessible;
            }
            // Validate the address that will receive the staged bytes. Resolving
            // a changing opening alias again could verify one file and replace another.
            if (verifyOriginal) requireUnchangedFile(baseline, target, operation);
            boolean targetExists = requireRegularSaveTargetOrMissing(target);
            String prefix = "." + sanitizePrefix(target.getFileName().toString()) + ".";
            if (prefix.length() < 3) prefix = ".tuink.";
            operation.checkCancelled();
            tempFile = createSaveTempFile(target.getParent(), prefix, targetExists);
            DocumentCodec.EncodedContent encoded;
            try (OutputStream output = publicationFiles.openStaged(tempFile)) {
                encoded = DocumentCodec.write(output, request.lines(), request.metadata(), operation);
            }
            operation.checkCancelled();
            FileBaseline savedBaseline = new FileBaseline(target, encoded.fingerprint().sha256(), encoded.fingerprint().byteSize());
            // Every possible successful receipt exists before a filesystem call
            // can publish bytes. Choosing one after commit cannot allocate.
            SaveReceipts receipts = new SaveReceipts(
                new SavedDocument(savedResource, encoded.metadata(), savedBaseline, PublicationKind.EXCLUSIVE_CREATE),
                new SavedDocument(savedResource, encoded.metadata(), savedBaseline, PublicationKind.ATOMIC_REPLACEMENT),
                new SavedDocument(savedResource, encoded.metadata(), savedBaseline, PublicationKind.REPLACEMENT),
                new SavedDocument(savedResource, encoded.metadata(), savedBaseline, PublicationKind.STREAM_WRITE));
            guard = FileSaveGuard.acquire(target);
            requireReadyToPublish(approved, baseline, verifyOriginal, requested, target, operation);
            boolean currentTargetExists = requireRegularSaveTargetOrMissing(target);
            if (disposition == SaveDisposition.CREATE_NEW && currentTargetExists) {
                throw new FileAlreadyExistsException(target.toString());
            }
            FileSaveAttributes attributes = currentTargetExists ? FileSaveAttributes.read(target) : null;
            if (attributes != null) attributes.apply(tempFile);
            requireReadyToPublish(approved, baseline, verifyOriginal, requested, target, operation);
            if (disposition == SaveDisposition.CREATE_NEW) {
                return publishNewFile(tempFile, target, publication, receipts, operation, encoded.fingerprint().byteSize());
            }
            operation.beginPublication();
            publication.attempted = true;
            try {
                publicationFiles.move(tempFile, target, true);
                publication.committed = true;
                return receipts.atomic;
            } catch (AtomicMoveNotSupportedException unsupported) {
                // This specified failure means no move occurred. Recheck all
                // conditions before attempting the weaker portable operation.
                publication.attempted = false;
                operation.resumePreparation();
                requireReadyToPublish(approved, baseline, verifyOriginal, requested, target, operation);
                operation.beginPublication();
                publication.attempted = true;
                publicationFiles.move(tempFile, target, false);
                publication.committed = true;
                return receipts.replacement;
            }
        } catch (IOException | RuntimeException | Error error) {
            if (publication.attempted && !publication.committed) {
                try {
                    failure = new StorageWriteException(unconfirmedSave.getMessage(), error,
                        StorageWriteException.Outcome.UNKNOWN, savedResource);
                } catch (OutOfMemoryError exhausted) {
                    failure = unconfirmedSave;
                    suppress(failure, error);
                    suppress(failure, exhausted);
                }
                throw (StorageWriteException) failure;
            }
            if (error instanceof OutOfMemoryError) {
                failure = new IOException("Not enough memory to save this document", error);
                throw (IOException) failure;
            }
            failure = error;
            throw error;
        } finally {
            if (guard != null) {
                try { guard.close(); }
                catch (IOException | RuntimeException | Error cleanupFailure) { suppress(failure, cleanupFailure); }
            }
            if (tempFile != null) {
                try { Files.deleteIfExists(tempFile); }
                catch (IOException | RuntimeException | Error cleanupFailure) {
                    if (failure != null) suppress(failure, cleanupFailure);
                    else if (!publication.committed) throw new IOException("Cannot remove a staged save file", cleanupFailure);
                    // A committed target cannot be rolled back by cleanup failure.
                }
            }
        }
    }

    private void requireReadyToPublish(FileSaveTarget approved, FileBaseline baseline, boolean verifyOriginal,
                                               Path requested, Path target, StorageOperation operation) throws IOException {
        operation.checkCancelled();
        if (verifyOriginal) requireUnchangedFile(baseline, target, operation);
        if (!target.equals(FileSaveGuard.canonicalTarget(requested))) {
            throw new IOException("Save target changed outside TUInk during saving: " + requested
                + ". Choose the target again and retry Save As.");
        }
        requireRegularSaveTargetOrMissing(target);
        // The stronger approval stamp is checked last: a byte-identical atomic
        // replacement must not pass merely because the loaded baseline matches.
        if (approved != null) approved.target.requireUnchanged(operation);
        operation.checkCancelled();
    }

    private SavedDocument publishNewFile(Path tempFile, Path target, Publication publication, SaveReceipts receipts,
                                         StorageOperation operation, long stagedSize)
        throws IOException {
        // Allocate the portable fallback's state even before the first publication
        // attempt; a successful link returns its preallocated receipt immediately.
        byte[] buffer = new byte[8192];
        ByteBuffer bytes = ByteBuffer.wrap(buffer);
        Throwable linkFailure;
        operation.beginPublication();
        publication.attempted = true;
        try {
            publicationFiles.createLink(target, tempFile);
            publication.committed = true;
            return receipts.exclusive;
        } catch (FileAlreadyExistsException exists) {
            publication.attempted = false; // CREATE_NEW conflict is a definite non-commit.
            throw exists;
        } catch (UnsupportedOperationException unsupported) {
            publication.attempted = false;
            linkFailure = unsupported;
            operation.resumePreparation();
        } catch (IOException ambiguous) {
            // Some providers cannot link. Retain the uncertainty if an attempted
            // link could have published before reporting its failure.
            linkFailure = ambiguous;
        }
        boolean linkMayHavePublished = publication.attempted;
        SeekableByteChannel output;
        if (!operation.publishing()) operation.beginPublication();
        publication.attempted = true;
        try {
            output = publicationFiles.createNew(target);
        } catch (FileAlreadyExistsException exists) {
            publication.attempted = linkMayHavePublished;
            suppress(exists, linkFailure);
            throw exists;
        } catch (IOException | RuntimeException | Error openFailure) {
            suppress(openFailure, linkFailure);
            throw openFailure;
        }
        try (output; InputStream input = Files.newInputStream(tempFile)) {
            long copied = 0L;
            operation.progress(StorageOperation.Phase.PUBLISHING, copied, stagedSize);
            int read;
            while ((read = input.read(buffer)) != -1) {
                bytes.clear().limit(read);
                while (bytes.hasRemaining()) output.write(bytes);
                copied += read;
                operation.progress(StorageOperation.Phase.PUBLISHING, copied, stagedSize);
            }
        } catch (IOException | RuntimeException | Error incomplete) {
            // Never delete by name: another writer may already have replaced it.
            suppress(incomplete, linkFailure);
            throw incomplete;
        }
        publication.committed = true;
        return receipts.stream;
    }

    private static void suppress(Throwable primary, Throwable secondary) {
        if (primary == null || primary == secondary) return;
        try { primary.addSuppressed(secondary); }
        catch (RuntimeException | Error unavailable) { /* Preserve the original outcome under memory pressure. */ }
    }

    private static FileBaseline fileBaseline(ResourceVersion version) throws IOException {
        if (version == null) return null;
        if (version instanceof FileBaseline baseline) return baseline;
        throw new IOException("File version belongs to another storage provider");
    }

    private void requireUnchangedFile(FileBaseline baseline, Path requested, StorageOperation operation)
        throws IOException {
        if (baseline == null) throw new IOException("Cannot verify the original file: " + requested
            + ". Use Save As with a different name, or reopen the file.");
        baseline.requireUnchanged(requested, baselineInput, operation);
    }

    /** Resolves parent aliases before dot segments while retaining a final symlink alias. */
    public static Path resolvePath(Path requested) throws IOException { return resolveDocumentPath(requested); }

    static Path resolveDocumentPath(Path requested) throws IOException {
        Path absolute = requested.toAbsolutePath();
        Path leaf = absolute.getFileName();
        if (leaf == null || leaf.toString().equals(".") || leaf.toString().equals("..")) {
            throw new IOException("Document target must be a file: " + requested);
        }
        return JvmFilePaths.resolveEntry(absolute);
    }

    private static boolean requireRegularSaveTargetOrMissing(Path target) throws IOException {
        BasicFileAttributes attributes;
        try { attributes = Files.readAttributes(target, BasicFileAttributes.class); }
        catch (NoSuchFileException missing) {
            if (Files.isSymbolicLink(target)) throw missing;
            return false;
        }
        if (attributes.isDirectory()) throw new IOException("Save target is a directory: " + target);
        if (!attributes.isRegularFile()) throw new IOException("Save target is not a regular file: " + target);
        return true;
    }

    private static String sanitizePrefix(String fileName) {
        StringBuilder cleaned = new StringBuilder(32);
        for (int index = 0; index < fileName.length() && cleaned.length() < 32; index++) {
            char character = fileName.charAt(index);
            if (Character.isLetterOrDigit(character) || character == '_' || character == '-') cleaned.append(character);
        }
        if (cleaned.length() < 3) cleaned.append("tuink");
        return cleaned.substring(0, Math.max(3, Math.min(cleaned.length(), 32)));
    }

    private static Path createSaveTempFile(Path directory, String prefix, boolean exists) throws IOException {
        if (exists) return Files.createTempFile(directory, prefix, ".tmp");
        try {
            return Files.createTempFile(directory, prefix, ".tmp", PosixFilePermissions.asFileAttribute(NEW_FILE_PERMISSIONS));
        } catch (UnsupportedOperationException unsupported) {
            return Files.createTempFile(directory, prefix, ".tmp");
        }
    }

    private record FileId(Path path) implements ResourceId {
        @Override public String provider() { return "jvm-file"; }
    }

    private static final class FileResource implements DocumentResource {
        private final JvmDocumentStorage storage;
        private final Path path;
        private final FileId id;
        private final String displayName;
        private final String nameHint;
        FileResource(JvmDocumentStorage storage, Path path) {
            this.storage = storage;
            this.path = path;
            id = new FileId(path);
            displayName = path.toString();
            nameHint = path.getFileName() == null ? "" : path.getFileName().toString();
        }
        @Override public ResourceId id() { return id; }
        @Override public String displayName() { return displayName; }
        @Override public String nameHint() { return nameHint; }
        @Override public DocumentStorage storage() { return storage; }
        @Override public boolean equals(Object other) {
            return other instanceof FileResource file && storage == file.storage && id.equals(file.id);
        }
        @Override public int hashCode() { return 31 * System.identityHashCode(storage) + id.hashCode(); }
        @Override public String toString() { return displayName; }
    }

    private record FileSaveTarget(JvmDocumentStorage storage, ResourceId originalId, PreparedSaveTarget target,
                                  DocumentResource requestedResource, DocumentResource physicalResource,
                                  boolean currentTarget) implements SaveTarget {
        @Override public boolean exists() { return target.exists(); }
        @Override public Set<StorageCapability> capabilities() { return CAPABILITIES; }
    }

    private record SaveReceipts(SavedDocument exclusive, SavedDocument atomic, SavedDocument replacement,
                                 SavedDocument stream) {}
    private static final class Publication { boolean attempted; boolean committed; }

    /** Publication-only seam: tests can model a provider reporting failure after a real commit. */
    interface PublicationFiles {
        default OutputStream openStaged(Path staged) throws IOException { return Files.newOutputStream(staged); }
        default void createLink(Path target, Path staged) throws IOException { Files.createLink(target, staged); }
        default void move(Path staged, Path target, boolean atomic) throws IOException {
            if (atomic) Files.move(staged, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            else Files.move(staged, target, StandardCopyOption.REPLACE_EXISTING);
        }
        default SeekableByteChannel createNew(Path target) throws IOException {
            Set<StandardOpenOption> options = Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            try { return Files.newByteChannel(target, options, PosixFilePermissions.asFileAttribute(NEW_FILE_PERMISSIONS)); }
            catch (UnsupportedOperationException unsupported) { return Files.newByteChannel(target, options); }
        }
    }
}
