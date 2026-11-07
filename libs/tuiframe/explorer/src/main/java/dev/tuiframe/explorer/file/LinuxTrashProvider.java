package dev.tuiframe.explorer.file;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import static java.nio.file.StandardOpenOption.CREATE_NEW;
import static java.nio.file.StandardOpenOption.WRITE;

/** Freedesktop trash storage for local Unix filesystems; requires no graphical session. */
public final class LinuxTrashProvider implements TrashProvider {
    private static final Set<PosixFilePermission> PRIVATE = PosixFilePermissions.fromString("rwx------");
    private final Path dataHome;
    private volatile long uid;
    private final boolean enabled;

    /** An explicit data home and effective user ID also allow isolated provider tests. */
    public LinuxTrashProvider(Path dataHome, long uid) {
        this(dataHome, uid, true);
    }

    private LinuxTrashProvider(Path dataHome, long uid, boolean enabled) {
        this.dataHome = dataHome.toAbsolutePath().normalize();
        this.uid = uid;
        this.enabled = enabled;
    }

    public static TrashProvider system() {
        try {
            String configured = System.getenv("XDG_DATA_HOME");
            Path data = configured == null || configured.isBlank() || !Path.of(configured).isAbsolute()
                ? Path.of(System.getProperty("user.home"), ".local", "share") : Path.of(configured);
            return new LinuxTrashProvider(data, -1L, System.getProperty("os.name", "").startsWith("Linux"));
        } catch (RuntimeException invalidEnvironment) {
            return new LinuxTrashProvider(Path.of("/"), -1L, false);
        }
    }

    @Override public Availability availability(Path source) {
        try {
            List<Location> candidates = locations(source);
            for (Location candidate : candidates) {
                try { checkLocation(candidate); return Availability.supported(); }
                catch (IOException unavailable) { /* A volume may offer the other standard trash location. */ }
            }
            return Availability.unavailable("The system trash is unavailable for this location");
        } catch (IOException | RuntimeException failure) {
            return Availability.unavailable(message(failure));
        }
    }

    @Override public ExplorerFileService.Result trash(ExplorerFileService.Target source, Transfer transfer) {
        IOException last = null;
        List<Location> candidates;
        try { candidates = locations(source.path()); }
        catch (IOException | RuntimeException failure) { return failed(source, failure); }
        for (Location location : candidates) {
            Path info = null;
            try {
                checkLocation(location);
                prepare(location);
                String entry = UUID.randomUUID() + "-" + safeName(source.path());
                Path destination = location.root().resolve("files").resolve(entry);
                info = location.root().resolve("info").resolve(entry + ".trashinfo");
                String original = encodedOriginal(source.path(), location);
                Files.writeString(info, "[Trash Info]\nPath=" + original + "\nDeletionDate="
                    + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")) + "\n",
                    StandardCharsets.UTF_8, CREATE_NEW, WRITE);
                Files.setPosixFilePermissions(info, PosixFilePermissions.fromString("rw-------"));
                var moved = transfer.move(source, destination);
                if (!moved.destinationPublished()) Files.deleteIfExists(info);
                Set<Path> removed = moved.succeeded() ? Set.of(source.path()) : moved.removedPaths();
                return new ExplorerFileService.Result(ExplorerFileService.Action.TRASH, source.path(), null,
                    moved.succeeded(), moved.destinationPublished(), moved.failure(), removed);
            } catch (IOException | RuntimeException failure) {
                last = asIo(failure);
                if (info != null) {
                    try { Files.deleteIfExists(info); }
                    catch (IOException cleanup) { last.addSuppressed(cleanup); }
                    // Once an entry was reserved, do not retry a mutation in another location.
                    return failed(source, last);
                }
            }
        }
        return failed(source, last == null ? new IOException("The system trash is unavailable") : last);
    }

    private List<Location> locations(Path source) throws IOException {
        if (!enabled) throw new IOException("System trash is not supported on this platform");
        long user = effectiveUid();
        Path entry = source.toAbsolutePath().normalize();
        Path parent = entry.getParent();
        if (parent == null) throw new IOException("The filesystem root cannot be sent to trash");
        var attributes = Files.readAttributes(entry, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        Path storageEntry = attributes.isSymbolicLink() ? parent.toRealPath() : entry.toRealPath();
        var store = Files.getFileStore(storageEntry);
        if (!store.supportsFileAttributeView("posix")) throw new IOException("This filesystem does not support a private Unix trash");
        Path homeAncestor = existingAncestor(dataHome);
        if (store.equals(Files.getFileStore(homeAncestor)))
            return checkedLocations(entry, List.of(new Location(dataHome.resolve("Trash"), null, false)));

        Path top = storageEntry;
        if (!Files.isDirectory(top)) top = top.getParent();
        for (int depth = 0; top.getParent() != null; depth++) {
            if (depth >= 256) throw new IOException("Filesystem hierarchy is too deep");
            Path upper = top.getParent();
            if (!store.equals(Files.getFileStore(upper))) break;
            top = upper;
        }
        List<Location> result = new ArrayList<>();
        Path shared = top.resolve(".Trash");
        if (validShared(shared)) result.add(new Location(shared.resolve(Long.toString(user)), top, true));
        result.add(new Location(top.resolve(".Trash-" + user), top, false));
        return checkedLocations(entry, result);
    }

    private static List<Location> checkedLocations(Path entry, List<Location> locations) throws IOException {
        Path actualEntry = entry.getParent().toRealPath().resolve(entry.getFileName());
        for (Location location : locations) {
            Path trash = exists(location.root()) ? location.root().toRealPath() : location.root().toAbsolutePath().normalize();
            if (actualEntry.startsWith(trash))
                throw new IOException("An entry already in the system trash cannot be trashed again");
            if (trash.startsWith(actualEntry)) throw new IOException("The source contains its system trash directory");
        }
        return List.copyOf(locations);
    }

    private void checkLocation(Location location) throws IOException {
        Path root = location.root();
        if (location.shared() && !validShared(root.getParent())) throw new IOException("The shared trash directory is unsafe");
        if (exists(root)) {
            privateDirectory(root);
            for (String name : List.of("files", "info")) {
                Path child = root.resolve(name);
                if (exists(child)) privateDirectory(child);
            }
            if (!Files.isWritable(root)) throw new IOException("The trash directory is not writable");
        } else {
            Path ancestor = existingAncestor(root.getParent());
            if (!Files.isDirectory(ancestor) || !Files.isWritable(ancestor))
                throw new IOException("The trash directory cannot be created");
        }
    }

    private void prepare(Location location) throws IOException {
        if (location.top() == null) Files.createDirectories(dataHome);
        if (location.shared() && !validShared(location.root().getParent()))
            throw new IOException("The shared trash directory changed");
        createPrivate(location.root());
        createPrivate(location.root().resolve("files"));
        createPrivate(location.root().resolve("info"));
    }

    private void createPrivate(Path directory) throws IOException {
        try { Files.createDirectory(directory, PosixFilePermissions.asFileAttribute(PRIVATE)); }
        catch (FileAlreadyExistsException existing) { /* Validate the existing object below. */ }
        privateDirectory(directory);
    }

    private void privateDirectory(Path directory) throws IOException {
        var attributes = Files.readAttributes(directory, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isDirectory() || attributes.isSymbolicLink()) throw new IOException("A trash directory is not a real directory");
        long owner = ((Number) Files.getAttribute(directory, "unix:uid", LinkOption.NOFOLLOW_LINKS)).longValue();
        if (owner != effectiveUid()) throw new IOException("A trash directory belongs to another user");
        Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(directory, LinkOption.NOFOLLOW_LINKS);
        if (!permissions.equals(PRIVATE)) throw new IOException("Trash directory permissions must be 0700");
    }

    private static boolean validShared(Path directory) throws IOException {
        if (!exists(directory)) return false;
        var attributes = Files.readAttributes(directory, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        return attributes.isDirectory() && !attributes.isSymbolicLink()
            && (((Number) Files.getAttribute(directory, "unix:mode", LinkOption.NOFOLLOW_LINKS)).intValue() & 01000) != 0;
    }

    private long effectiveUid() throws IOException {
        if (uid < 0L) uid = ((Number) Files.getAttribute(
            Path.of("/proc", Long.toString(ProcessHandle.current().pid())), "unix:uid")).longValue();
        return uid;
    }

    private static Path existingAncestor(Path path) throws IOException {
        for (int depth = 0; path != null && depth < 256; depth++, path = path.getParent()) {
            if (exists(path)) return path.toRealPath();
        }
        throw new IOException("No accessible parent directory");
    }

    private static boolean exists(Path path) throws IOException {
        try { Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS); return true; }
        catch (NoSuchFileException absent) { return false; }
    }

    private static String encodedOriginal(Path source, Location location) throws IOException {
        Path absolute = source.toAbsolutePath().normalize();
        if (location.top() == null) return absolute.toUri().getRawPath();
        Path canonical = absolute.getParent().toRealPath().resolve(absolute.getFileName());
        String original = canonical.toUri().getRawPath();
        String top = location.top().toUri().getRawPath();
        if (!top.endsWith("/")) top += '/';
        if (!original.startsWith(top)) throw new IOException("The source no longer belongs to this trash filesystem");
        return original.substring(top.length());
    }

    private static String safeName(Path source) {
        String name = source.getFileName().toString();
        var result = new StringBuilder();
        for (int index = 0; index < name.length() && result.length() < 60; index++) {
            char c = name.charAt(index);
            result.append(c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9' || c == '.' || c == '-' ? c : '_');
        }
        return result.isEmpty() ? "entry" : result.toString();
    }

    private static ExplorerFileService.Result failed(ExplorerFileService.Target source, Exception failure) {
        return new ExplorerFileService.Result(ExplorerFileService.Action.TRASH, source.path(), null, false, false, asIo(failure));
    }

    private static IOException asIo(Exception failure) { return failure instanceof IOException io ? io : new IOException(message(failure), failure); }
    private static String message(Exception failure) { return failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage(); }
    private record Location(Path root, Path top, boolean shared) {}
}
