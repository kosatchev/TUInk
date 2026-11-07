package dev.tuiframe.explorer.file;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.channels.Channels;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.DosFileAttributeView;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.LinkedHashSet;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** Filesystem mutations for the explorer. Every method runs on an I/O worker. */
public final class ExplorerFileService {
    public enum Action {
        CREATE_FILE, CREATE_DIRECTORY, COPY, MOVE, RENAME, DELETE, TRASH;

        public boolean relocates() { return this == MOVE || this == RENAME; }
        public boolean removes() { return this == DELETE || this == TRASH; }
    }

    public enum RemovalDisposition { NONE, PERMANENT, TRASHED }

    /** Protocol seam for deterministic copy/publication failures in tests. */
    interface MoveObserver {
        default void copied(Path source, Path stagingCopy) throws IOException {}
        default void published(Path source, Path destination) throws IOException {}
    }

    private final MoveObserver moveObserver;
    private final TrashProvider trash;

    public ExplorerFileService() { this(new MoveObserver() {}, LinuxTrashProvider.system()); }
    public ExplorerFileService(TrashProvider trash) { this(new MoveObserver() {}, trash); }
    ExplorerFileService(MoveObserver moveObserver) {
        this(moveObserver, LinuxTrashProvider.system());
    }
    ExplorerFileService(MoveObserver moveObserver, TrashProvider trash) {
        this.moveObserver = Objects.requireNonNull(moveObserver, "moveObserver");
        this.trash = Objects.requireNonNull(trash, "trash");
    }

    /** Identity observed before the user confirms a destructive operation. */
    public record Target(Path path, Object key, boolean directory, boolean link, long size, FileTime modified) {
        public Target { path = path.toAbsolutePath().normalize(); }
    }

    /** Identities captured before publication; later filesystem observations cannot authorize a replacement. */
    public record IdentityChange(Target before, Target after) {
        public IdentityChange { Objects.requireNonNull(before); Objects.requireNonNull(after); }
    }

    /** A failure may follow a partial mutation; callers must reconcile the actual paths. */
    public record Result(Action action, Path source, Path destination, boolean succeeded,
        boolean destinationPublished, IOException failure, Set<Path> removedPaths, List<IdentityChange> identities) {
        public Result {
            Objects.requireNonNull(action, "action");
            removedPaths = Set.copyOf(removedPaths);
            identities = List.copyOf(identities);
        }
        public Result(Action action, Path source, Path destination, boolean succeeded,
            boolean destinationPublished, IOException failure, Set<Path> removedPaths) {
            this(action, source, destination, succeeded, destinationPublished, failure, removedPaths, List.of());
        }
        public Result(Action action, Path source, Path destination, boolean succeeded,
            boolean destinationPublished, IOException failure) {
            this(action, source, destination, succeeded, destinationPublished, failure,
                action.removes() && succeeded && source != null ? Set.of(source) : Set.of());
        }
        public RemovalDisposition removalDisposition() {
            return switch (action) {
                case DELETE -> RemovalDisposition.PERMANENT;
                case TRASH -> RemovalDisposition.TRASHED;
                default -> RemovalDisposition.NONE;
            };
        }
        public boolean partiallyChanged() { return !succeeded && (destinationPublished || !removedPaths.isEmpty()); }
    }

    public TrashProvider.Availability trashAvailability(Path source) { return trash.availability(source); }

    public Result trash(Target target) {
        try {
            requireUnchanged(target);
            return trash.trash(target, this::moveTo);
        } catch (IOException | RuntimeException failure) {
            return new Result(Action.TRASH, target.path(), null, false, false, asIo(failure));
        }
    }

    public Target inspect(Path path) throws IOException {
        Path normalized = path.toAbsolutePath().normalize();
        var attributes = Files.readAttributes(normalized, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        return new Target(normalized, attributes.fileKey(), attributes.isDirectory(), attributes.isSymbolicLink(),
            attributes.size(), attributes.lastModifiedTime());
    }

    Target publishedTarget(Result result) throws IOException {
        for (var identity : result.identities())
            if (identity.after().path().equals(result.destination())) return identity.after();
        return inspect(result.destination());
    }

    void requirePublishedIdentities(Result result) throws IOException {
        for (var identity : result.identities()) requireUnchanged(identity.after());
    }

    public Result create(Path parent, String name, boolean directory) {
        Action action = directory ? Action.CREATE_DIRECTORY : Action.CREATE_FILE;
        Path destination = null;
        try {
            Path component = nameComponent(name);
            destination = parent.toAbsolutePath().normalize().resolve(component);
            if (directory) Files.createDirectory(destination);
            else Files.createFile(destination);
            return new Result(action, null, destination, true, true, null);
        } catch (IOException | RuntimeException failure) {
            return new Result(action, null, destination, false, false, asIo(failure));
        }
    }

    /** Same-parent relocation with exclusive destination naming. */
    public Result rename(Target target, String name) {
        Path destination = null;
        try {
            Path component = nameComponent(name);
            requireUnchanged(target);
            Path parent = target.path().getParent();
            if (parent == null) throw new IOException("The filesystem root cannot be renamed");
            destination = parent.resolve(component);
            if (name.equals(target.path().getFileName().toString()))
                return new Result(Action.RENAME, target.path(), destination, true, false, null);
            if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS))
                throw new IOException("The destination already contains this name");
            Target before = inspect(target.path());
            Files.move(target.path(), destination);
            return new Result(Action.RENAME, target.path(), destination, true, true, null, Set.of(),
                List.of(new IdentityChange(before, at(before, destination))));
        } catch (IOException | RuntimeException failure) {
            return new Result(Action.RENAME, target.path(), destination, false, false, asIo(failure));
        }
    }

    private static Path nameComponent(String name) throws IOException {
        if (name.isBlank()) throw new IOException("Enter a name");
        Path component = Path.of(name);
        if (component.isAbsolute() || component.getNameCount() != 1 || name.equals(".") || name.equals("..")
            || name.indexOf('/') >= 0 || name.indexOf('\\') >= 0)
            throw new IOException("Enter a single file or folder name");
        return component;
    }

    public Result move(Target target, Path parent) {
        Path destination = parent.toAbsolutePath().normalize().resolve(target.path().getFileName());
        return moveTo(target, destination);
    }

    /** Creates an independent verified copy; links are copied as links, never traversed. */
    public Result copy(Target target, Path parent) {
        return copy(target, parent, null);
    }

    /** Redo requires the captured original contents before publication, even if the source changes mid-copy. */
    Result copy(Target target, Path parent, CopyGuard requiredContents) {
        Path destination = null;
        try {
            requireUnchanged(target);
            if (target.path().getFileName() == null) throw new IOException("The filesystem root cannot be copied");
            Path normalizedParent = parent.toAbsolutePath().normalize();
            destination = normalizedParent.resolve(target.path().getFileName());
            if (!Files.isDirectory(normalizedParent)) throw new IOException("Choose an existing destination folder");
            Path realParent = normalizedParent.toRealPath();
            if (target.directory() && realParent.startsWith(target.path().toRealPath()))
                throw new IOException("A folder cannot be copied into itself or its descendants");
            if (destination.equals(target.path()) || Files.exists(destination, LinkOption.NOFOLLOW_LINKS))
                throw new IOException("The destination already contains this name");
            return copyAndPublish(target, destination, false, requiredContents);
        } catch (IOException | RuntimeException failure) {
            return new Result(Action.COPY, target.path(), destination, false, false, asIo(failure));
        }
    }

    Result moveTo(Target target, Path destination) {
        destination = destination.toAbsolutePath().normalize();
        Path parent = destination.getParent();
        try {
            requireUnchanged(target);
            if (parent == null || !Files.isDirectory(parent)) throw new IOException("Choose an existing destination folder");
            Path realParent = parent.toRealPath();
            if (target.directory() && realParent.startsWith(target.path().toRealPath()))
                throw new IOException("A folder cannot be moved into itself or its descendants");
            if (destination.equals(target.path()) || Files.exists(destination, LinkOption.NOFOLLOW_LINKS))
                throw new IOException("The destination already contains this name");
            Path sourceStorePath = target.link() ? target.path().getParent() : target.path();
            if (!Files.getFileStore(sourceStorePath).equals(Files.getFileStore(realParent)))
                return moveAcrossFileStores(target, destination);
            Target before = inspect(target.path());
            Files.move(target.path(), destination);
            return new Result(Action.MOVE, target.path(), destination, true, true, null, Set.of(),
                List.of(new IdentityChange(before, at(before, destination))));
        } catch (IOException | RuntimeException failure) {
            return new Result(Action.MOVE, target.path(), destination, false, false, asIo(failure));
        }
    }

    public Result delete(Target target) {
        Set<Path> removed = new LinkedHashSet<>();
        try {
            requireUnchanged(target);
            // walkFileTree does not follow symbolic links; a link is deleted as a single entry.
            Files.walkFileTree(target.path(), new SimpleFileVisitor<>() {
                @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                    Files.delete(file); removed.add(file); return FileVisitResult.CONTINUE;
                }
                @Override public FileVisitResult postVisitDirectory(Path directory, IOException failure) throws IOException {
                    if (failure != null) throw failure;
                    Files.delete(directory); removed.add(directory); return FileVisitResult.CONTINUE;
                }
            });
            return new Result(Action.DELETE, target.path(), null, true, false, null, removed);
        } catch (IOException | RuntimeException failure) {
            return new Result(Action.DELETE, target.path(), null, false, false, asIo(failure), removed);
        }
    }

    /** An inverse creation never recursively deletes content added after the creation. */
    public Result removeEmptyCreated(Target target) {
        try {
            if (target.directory()) requireUnchanged(target);
            else requireExactUnchanged(target);
            if (target.link()) throw new IOException("The created entry changed into a symbolic link");
            if (!target.directory() && target.size() != 0L) throw new IOException("The created file is no longer empty");
            Files.delete(target.path()); // A nonempty directory is rejected atomically by the filesystem.
            return new Result(Action.DELETE, target.path(), null, true, false, null);
        } catch (IOException | RuntimeException failure) {
            return new Result(Action.DELETE, target.path(), null, false, false, asIo(failure));
        }
    }

    void requireExactUnchanged(Target expected) throws IOException {
        requireUnchanged(expected);
        Target current = inspect(expected.path());
        if (expected.size() != current.size() || !expected.modified().equals(current.modified()))
            throw new IOException("The created entry changed; its contents have been kept");
    }

    private void requireUnchanged(Target expected) throws IOException {
        Target current = inspect(expected.path());
        if (expected.directory() != current.directory() || expected.link() != current.link()
            || expected.key() != null && !Objects.equals(expected.key(), current.key())
            || expected.key() == null && (expected.size() != current.size() || !expected.modified().equals(current.modified())))
            throw new IOException("The selected entry changed; select it again before retrying");
    }

    /** Copies privately, verifies the copy, publishes once, then removes matching source entries. */
    Result moveAcrossFileStores(Target source, Path destination) {
        return copyAndPublish(source, destination, true, null);
    }

    private Result copyAndPublish(Target source, Path destination, boolean removeSource, CopyGuard requiredContents) {
        Action action = removeSource ? Action.MOVE : Action.COPY;
        Path staging = null;
        Path copy = null;
        Map<Path, Target> originals = new LinkedHashMap<>();
        Map<Path, DirectoryAttributes> directories = new LinkedHashMap<>();
        boolean published = false;
        boolean succeeded = false;
        Set<Path> removed = new LinkedHashSet<>();
        List<IdentityChange> identities = new ArrayList<>();
        IOException failure = null;
        try {
            requireUnchanged(source);
            staging = Files.createTempDirectory(destination.getParent(), removeSource ? ".tuiframe-move-" : ".tuiframe-copy-");
            copy = staging.resolve("entry");
            Path copyRoot = copy;
            Files.walkFileTree(source.path(), new SimpleFileVisitor<>() {
                @Override public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) throws IOException {
                    originals.put(directory, inspect(directory));
                    Path copied = copyRoot.resolve(source.path().relativize(directory));
                    DirectoryAttributes saved = DirectoryAttributes.capture(directory, attributes);
                    directories.put(directory, saved);
                    Files.copy(directory, copied, LinkOption.NOFOLLOW_LINKS, StandardCopyOption.COPY_ATTRIBUTES);
                    makeTemporaryDirectoryWritable(copied);
                    return FileVisitResult.CONTINUE;
                }
                @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                    if (!attributes.isRegularFile() && !attributes.isSymbolicLink())
                        throw new IOException("Only regular files, folders and symbolic links can be copied: " + file);
                    originals.put(file, inspect(file));
                    Path copied = copyRoot.resolve(source.path().relativize(file));
                    Files.copy(file, copied, LinkOption.NOFOLLOW_LINKS,
                        StandardCopyOption.COPY_ATTRIBUTES);
                    // COPY_ATTRIBUTES does not preserve Unix symlink timestamps on every provider.
                    if (attributes.isSymbolicLink()) Files.getFileAttributeView(copied, BasicFileAttributeView.class,
                        LinkOption.NOFOLLOW_LINKS).setTimes(attributes.lastModifiedTime(), null, null);
                    return FileVisitResult.CONTINUE;
                }
                @Override public FileVisitResult postVisitDirectory(Path directory, IOException failure) throws IOException {
                    if (failure != null) throw failure;
                    directories.get(directory).restore(copyRoot.resolve(source.path().relativize(directory)));
                    return FileVisitResult.CONTINUE;
                }
            });
            moveObserver.copied(source.path(), copy);
            for (Target original : originals.values()) {
                verifyCopiedEntry(original, copy.resolve(source.path().relativize(original.path())));
            }
            verifyOriginalInventory(source, originals);
            if (requiredContents != null
                && !requiredContents.sameContents(captureCopyGuard(copy, requiredContents.size())))
                throw new IOException("The copy source changed; copy it again to use its new contents");
            for (Target original : originals.values()) {
                Path relative = source.path().relativize(original.path());
                identities.add(new IdentityChange(original, at(inspect(copy.resolve(relative)), destination.resolve(relative))));
            }
            // Moving a directory between parents updates its '..' entry on Unix.
            // Keep our private root writable for that rename, then publish its original attributes.
            if (source.directory()) makeTemporaryDirectoryWritable(copy);
            Files.move(copy, destination);
            published = true;
            if (source.directory()) directories.get(source.path()).restore(destination);
            moveObserver.published(source.path(), destination);
            if (removeSource) Files.walkFileTree(source.path(), new SimpleFileVisitor<>() {
                private void check(Path path) throws IOException {
                    Target original = originals.get(path);
                    if (original == null) throw new IOException("The source folder changed; remaining files have been kept");
                    verifyCopiedEntry(original, destination.resolve(source.path().relativize(path)));
                }
                @Override public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) throws IOException {
                    check(directory); return FileVisitResult.CONTINUE;
                }
                @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                    check(file); Files.delete(file); removed.add(file); return FileVisitResult.CONTINUE;
                }
                @Override public FileVisitResult postVisitDirectory(Path directory, IOException failure) throws IOException {
                    if (failure != null) throw failure;
                    Files.delete(directory); removed.add(directory); return FileVisitResult.CONTINUE;
                }
            });
            succeeded = true;
        } catch (IOException | RuntimeException problem) {
            failure = asIo(problem);
            if (published) failure = new IOException(removeSource
                ? "Destination was copied, but some source entries could not be removed: " + failure.getMessage()
                : "The copy was published, but the operation could not finish: " + failure.getMessage(), failure);
        } finally {
            if (staging != null) {
                try { cleanupStaging(staging); }
                catch (IOException | RuntimeException cleanup) {
                    IOException cleanupFailure = asIo(cleanup);
                    if (failure == null) {
                        failure = new IOException("Temporary transfer files could not be removed: " + staging, cleanupFailure);
                        succeeded = false;
                    } else failure.addSuppressed(cleanupFailure);
                }
            }
        }
        return new Result(action, source.path(), destination, succeeded, published, failure, removed,
            succeeded ? identities : List.of());
    }

    private static Target at(Target target, Path path) {
        return new Target(path, target.key(), target.directory(), target.link(), target.size(), target.modified());
    }

    private static boolean sameIdentity(Target expected, Target observed) {
        return expected.path().equals(observed.path()) && expected.directory() == observed.directory()
            && expected.link() == observed.link() && (expected.key() != null
                ? expected.key().equals(observed.key())
                : expected.size() == observed.size() && expected.modified().equals(observed.modified()));
    }

    private static Path relocatedPath(Path path, Path source, Path destination) {
        return source != null && path.startsWith(source) ? destination.resolve(source.relativize(path)) : path;
    }

    /** Retains an unmet content guard while following only proved identity changes. */
    static Target reidentified(Target expected, Map<Path, IdentityChange> identities, Path source, Path destination,
        boolean preserveContent) {
        Path path = relocatedPath(expected.path(), source, destination);
        IdentityChange change = identities.get(expected.path());
        if (change == null || !sameIdentity(expected, change.before())) return at(expected, path);
        Target after = change.after();
        if (preserveContent && (expected.size() != change.before().size()
            || !expected.modified().equals(change.before().modified())))
            return new Target(after.path(), after.key(), expected.directory(), expected.link(), expected.size(), expected.modified());
        return after;
    }

    private void verifyOriginalInventory(Target source, Map<Path, Target> originals) throws IOException {
        Set<Path> seen = new HashSet<>();
        Files.walkFileTree(source.path(), new SimpleFileVisitor<>() {
            private void check(Path path) throws IOException {
                Target original = originals.get(path);
                if (original == null) throw new IOException("The source folder changed while being copied");
                requireUnchanged(original);
                Target current = inspect(path);
                if (original.size() != current.size() || !original.modified().equals(current.modified()))
                    throw new IOException("The source changed while being copied");
                seen.add(path);
            }
            @Override public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) throws IOException {
                check(directory); return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                check(file); return FileVisitResult.CONTINUE;
            }
        });
        if (seen.size() != originals.size()) throw new IOException("The source folder changed while being copied");
    }

    private void verifyCopiedEntry(Target original, Path copied) throws IOException {
        requireUnchanged(original);
        Target current = inspect(original.path());
        if (original.size() != current.size() && !original.directory()
            || !original.modified().equals(current.modified()) && !original.directory())
            throw new IOException("The source changed while being copied; remaining files have been kept");
        if (original.link()) {
            if (!Files.isSymbolicLink(copied)
                || !Files.readSymbolicLink(original.path()).equals(Files.readSymbolicLink(copied)))
                throw new IOException("The source symbolic link changed; it has been kept");
        } else {
            if (!original.directory() && Files.mismatch(original.path(), copied) != -1L)
                throw new IOException("The source bytes changed while being copied; the source has been kept");
            var sourcePosix = Files.getFileAttributeView(original.path(), PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
            var copyPosix = Files.getFileAttributeView(copied, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
            if (sourcePosix != null && copyPosix != null
                && !sourcePosix.readAttributes().permissions().equals(copyPosix.readAttributes().permissions()))
                throw new IOException("The source permissions changed while being copied; the source has been kept");
        }
    }

    /** Bounded immutable identities and byte digests; history never stores file contents. */
    static final class CopyGuard {
        private final Path root;
        private final Map<Path, CopyEntry> entries;

        private CopyGuard(Path root, Map<Path, CopyEntry> entries) {
            this.root = root;
            this.entries = Map.copyOf(entries);
        }
        Path root() { return root; }
        int size() { return entries.size(); }
        Target sourceTarget() { return entries.get(root.relativize(root)).target(); }
        List<IdentityChange> recreatedAs(CopyGuard next) {
            var changes = new ArrayList<IdentityChange>(entries.size());
            for (var entry : entries.entrySet())
                changes.add(new IdentityChange(entry.getValue().target(), next.entries.get(entry.getKey()).target()));
            return changes;
        }
        CopyGuard reidentified(Map<Path, IdentityChange> identities, Path source, Path destination) {
            if (identities.isEmpty() && source == null) return this;
            Path nextRoot = relocatedPath(root, source, destination);
            var next = new LinkedHashMap<Path, CopyEntry>();
            boolean changed = !root.equals(nextRoot);
            for (var entry : entries.entrySet()) {
                CopyEntry previous = entry.getValue();
                Target target = ExplorerFileService.reidentified(previous.target(), identities, source, destination, true);
                changed |= !target.equals(previous.target());
                next.put(entry.getKey(), new CopyEntry(target, previous.linkTarget(), previous.digest(),
                    previous.permissions(), previous.readOnly()));
            }
            return changed ? new CopyGuard(nextRoot, next) : this;
        }
        CopyGuard refreshDirectoryTimes(Map<Path, Target> parents) {
            var next = new LinkedHashMap<Path, CopyEntry>();
            boolean changed = false;
            for (var entry : entries.entrySet()) {
                CopyEntry previous = entry.getValue();
                Target current = parents.get(previous.target().path());
                if (previous.target().directory() && current != null && sameIdentity(previous.target(), current)) {
                    changed |= !current.equals(previous.target());
                    previous = new CopyEntry(current, previous.linkTarget(), previous.digest(),
                        previous.permissions(), previous.readOnly());
                }
                next.put(entry.getKey(), previous);
            }
            return changed ? new CopyGuard(root, next) : this;
        }
        boolean sameContents(CopyGuard other) { return matches(other, false); }
        private boolean matches(CopyGuard other, boolean identities) {
            if (!entries.keySet().equals(other.entries.keySet())) return false;
            for (var entry : entries.entrySet())
                if (!entry.getValue().matches(other.entries.get(entry.getKey()), identities, true)) return false;
            return true;
        }
    }

    static final class CopyGuardLimit extends IOException {
        CopyGuardLimit() { super("The copy exceeds the file undo history limit"); }
    }

    private record CopyEntry(Target target, Path linkTarget, byte[] digest,
        Set<PosixFilePermission> permissions, Boolean readOnly) {
        boolean matches(CopyEntry other, boolean identity, boolean compareTimes) {
            return other != null && target.directory() == other.target.directory() && target.link() == other.target.link()
                && (!identity || target.key() == null || Objects.equals(target.key(), other.target.key()))
                && (target.directory() || target.size() == other.target.size())
                && (!compareTimes || target.modified().equals(other.target.modified()))
                && Objects.equals(linkTarget, other.linkTarget) && Arrays.equals(digest, other.digest)
                && Objects.equals(permissions, other.permissions) && Objects.equals(readOnly, other.readOnly);
        }
    }

    CopyGuard captureCopyGuard(Path path, int maximumEntries) throws IOException {
        Path root = path.toAbsolutePath().normalize();
        Map<Path, CopyEntry> entries = new LinkedHashMap<>();
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            private void add(Path entry) throws IOException {
                if (entries.size() >= maximumEntries) throw new CopyGuardLimit();
                entries.put(root.relativize(entry), copyEntry(entry));
            }
            @Override public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) throws IOException {
                add(directory); return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                add(file); return FileVisitResult.CONTINUE;
            }
        });
        return new CopyGuard(root, entries);
    }

    void requireUnchangedCopy(CopyGuard expected) throws IOException {
        CopyGuard current;
        try { current = captureCopyGuard(expected.root(), expected.size()); }
        catch (CopyGuardLimit changed) { throw new IOException("The copied entry changed; its contents have been kept", changed); }
        if (!expected.matches(current, true))
            throw new IOException("The copied entry changed; its contents have been kept");
    }

    Result removeUnchangedCopy(CopyGuard expected) {
        Set<Path> removed = new LinkedHashSet<>();
        try {
            requireUnchangedCopy(expected);
            Files.walkFileTree(expected.root(), new SimpleFileVisitor<>() {
                private void check(Path path, boolean directoryTimes) throws IOException {
                    CopyEntry original = expected.entries.get(expected.root().relativize(path));
                    if (original == null || !original.matches(copyEntry(path), true, directoryTimes))
                        throw new IOException("The copied entry changed; remaining contents have been kept");
                }
                @Override public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) throws IOException {
                    check(directory, true); return FileVisitResult.CONTINUE;
                }
                @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                    check(file, true);
                    Files.delete(file); removed.add(file); return FileVisitResult.CONTINUE;
                }
                @Override public FileVisitResult postVisitDirectory(Path directory, IOException failure) throws IOException {
                    if (failure != null) throw failure;
                    // Removing our children changes only directory times; new children make delete fail.
                    check(directory, false);
                    Files.delete(directory); removed.add(directory); return FileVisitResult.CONTINUE;
                }
            });
            return new Result(Action.DELETE, expected.root(), null, true, false, null, removed);
        } catch (IOException | RuntimeException failure) {
            return new Result(Action.DELETE, expected.root(), null, false, false, asIo(failure), removed);
        }
    }

    private CopyEntry copyEntry(Path path) throws IOException {
        Target target = inspect(path);
        if (!target.directory() && !target.link()
            && !Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS).isRegularFile())
            throw new IOException("Only regular files, folders and symbolic links can be tracked by copy history");
        Path link = target.link() ? Files.readSymbolicLink(path) : null;
        byte[] digest = target.directory() || target.link() ? null : digest(path);
        var posix = target.link() ? null : Files.getFileAttributeView(path, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        var dos = target.link() ? null : Files.getFileAttributeView(path, DosFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        CopyEntry entry = new CopyEntry(target, link, digest,
            posix == null ? null : Set.copyOf(posix.readAttributes().permissions()),
            dos == null ? null : dos.readAttributes().isReadOnly());
        requireExactUnchanged(target);
        return entry;
    }

    private static byte[] digest(Path path) throws IOException {
        MessageDigest digest;
        try { digest = MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
        try (var channel = Files.newByteChannel(path, Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS));
             var input = Channels.newInputStream(channel)) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
        }
        return digest.digest();
    }

    private static void makeTemporaryDirectoryWritable(Path directory) throws IOException {
        var posix = Files.getFileAttributeView(directory, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (posix != null) {
            Set<PosixFilePermission> permissions = new HashSet<>(posix.readAttributes().permissions());
            permissions.add(PosixFilePermission.OWNER_WRITE);
            permissions.add(PosixFilePermission.OWNER_EXECUTE);
            posix.setPermissions(permissions);
        }
        var dos = Files.getFileAttributeView(directory, DosFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (dos != null && dos.readAttributes().isReadOnly()) dos.setReadOnly(false);
    }

    private record DirectoryAttributes(FileTime modified, FileTime accessed,
        Set<PosixFilePermission> permissions, Boolean readOnly) {
        static DirectoryAttributes capture(Path directory, BasicFileAttributes attributes) throws IOException {
            var posix = Files.getFileAttributeView(directory, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
            var dos = Files.getFileAttributeView(directory, DosFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
            return new DirectoryAttributes(attributes.lastModifiedTime(), attributes.lastAccessTime(),
                posix == null ? null : Set.copyOf(posix.readAttributes().permissions()),
                dos == null ? null : dos.readAttributes().isReadOnly());
        }
        void restore(Path directory) throws IOException {
            Files.getFileAttributeView(directory, BasicFileAttributeView.class, LinkOption.NOFOLLOW_LINKS)
                .setTimes(modified, accessed, null);
            if (readOnly != null) Files.getFileAttributeView(directory, DosFileAttributeView.class,
                LinkOption.NOFOLLOW_LINKS).setReadOnly(readOnly);
            if (permissions != null) Files.getFileAttributeView(directory, PosixFileAttributeView.class,
                LinkOption.NOFOLLOW_LINKS).setPermissions(permissions);
        }
    }

    private static void cleanupStaging(Path staging) throws IOException {
        if (!Files.exists(staging, LinkOption.NOFOLLOW_LINKS)) return;
        Files.walkFileTree(staging, new SimpleFileVisitor<>() {
            @Override public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) throws IOException {
                makeTemporaryDirectoryWritable(directory); return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                var dos = attributes.isSymbolicLink() ? null
                    : Files.getFileAttributeView(file, DosFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
                if (dos != null && dos.readAttributes().isReadOnly()) dos.setReadOnly(false);
                Files.delete(file); return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult postVisitDirectory(Path directory, IOException failure) throws IOException {
                if (failure != null) throw failure;
                Files.delete(directory); return FileVisitResult.CONTINUE;
            }
        });
    }

    private static IOException asIo(Exception failure) {
        return failure instanceof IOException io ? io : new IOException(failure.getMessage(), failure);
    }

}
