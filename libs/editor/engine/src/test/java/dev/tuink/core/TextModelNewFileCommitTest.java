package dev.tuink.core;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessMode;
import java.nio.file.CopyOption;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileStore;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.StandardOpenOption;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.FileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.UserPrincipalLookupService;
import java.nio.file.attribute.UserDefinedFileAttributeView;
import java.nio.file.spi.FileSystemProvider;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TextModelNewFileCommitTest {
    private static final String CONTENT = "edited\ncontent";
    private static final String EXTERNAL = "external content";

    @TempDir
    Path tempDir;

    @Test
    void publishesCompleteStagedBytesWithAnAtomicLink() throws IOException {
        assumeHardLinks();
        Fixture fixture = fixture(CommitBehavior.LINK, false);

        fixture.save();

        assertArrayEquals(fixture.expectedBytes(), Files.readAllBytes(fixture.target()));
        assertArrayEquals(fixture.expectedBytes(), fixture.provider().bytesAtLink);
        assertTrue(fixture.provider().sharedInodeAtLink);
        assertEquals(0, fixture.provider().exclusiveOpenAttempts);
        assertSame(fixture.metadata(), fixture.model().fileMetadata());
        assertEquals(fixture.target(), fixture.model().fileBaseline().target());
        assertNoStagedFiles();
    }

    @Test
    void rejectsAFileCreatedAtTheAtomicLinkBoundary() throws IOException {
        assumeHardLinks();
        Fixture fixture = fixture(CommitBehavior.LINK_RACE, false);
        DocumentFileMetadata beforeMetadata = fixture.model().fileMetadata();
        FileBaseline beforeBaseline = fixture.model().fileBaseline();

        assertThrows(FileAlreadyExistsException.class, fixture::save);

        assertEquals(EXTERNAL, Files.readString(fixture.target()));
        assertSame(beforeMetadata, fixture.model().fileMetadata());
        assertSame(beforeBaseline, fixture.model().fileBaseline());
        assertEquals(0, fixture.provider().exclusiveOpenAttempts);
        assertNoStagedFiles();
    }

    @Test
    void writesExclusivelyWhenHardLinksAreUnsupported() throws IOException {
        Fixture fixture = fixture(CommitBehavior.UNSUPPORTED_LINK, false);

        fixture.save();

        assertArrayEquals(fixture.expectedBytes(), Files.readAllBytes(fixture.target()));
        assertEquals(1, fixture.provider().successfulExclusiveOpens);
        assertTrue(fixture.provider().requestedPermissions.contains(PosixFilePermission.OWNER_READ));
        assertTrue(fixture.provider().requestedPermissions.contains(PosixFilePermission.OWNER_WRITE));
        assertFalse(fixture.provider().requestedPermissions.contains(PosixFilePermission.OWNER_EXECUTE));
        assertSame(fixture.metadata(), fixture.model().fileMetadata());
        assertNotNull(fixture.model().fileBaseline());
        assertNoStagedFiles();
    }

    @Test
    void writesExclusivelyWhenTheProviderReportsALinkIoFailure() throws IOException {
        Fixture fixture = fixture(CommitBehavior.LINK_IO_FAILURE, false);

        fixture.save();

        assertArrayEquals(fixture.expectedBytes(), Files.readAllBytes(fixture.target()));
        assertEquals(1, fixture.provider().successfulExclusiveOpens);
        assertNoStagedFiles();
    }

    @Test
    void supportsExclusiveCreationWithoutPosixAttributes() throws IOException {
        Fixture fixture = fixture(CommitBehavior.UNSUPPORTED_LINK, true);

        fixture.save();

        assertArrayEquals(fixture.expectedBytes(), Files.readAllBytes(fixture.target()));
        assertEquals(2, fixture.provider().exclusiveOpenAttempts);
        assertEquals(1, fixture.provider().successfulExclusiveOpens);
        assertNoStagedFiles();
    }

    @Test
    void rejectsAFileCreatedBeforeThePortableExclusiveOpen() throws IOException {
        Fixture fixture = fixture(CommitBehavior.OPEN_RACE, false);
        DocumentFileMetadata beforeMetadata = fixture.model().fileMetadata();
        FileBaseline beforeBaseline = fixture.model().fileBaseline();

        FileAlreadyExistsException failure = assertThrows(FileAlreadyExistsException.class, fixture::save);

        assertFalse(failure.getMessage().contains("incomplete"));
        assertEquals(EXTERNAL, Files.readString(fixture.target()));
        assertSame(beforeMetadata, fixture.model().fileMetadata());
        assertSame(beforeBaseline, fixture.model().fileBaseline());
        assertEquals(0, fixture.provider().successfulExclusiveOpens);
        assertNoStagedFiles();
    }

    @Test
    void retainsPartialNewFileAndMetadataWhenPortableWritingFails() throws IOException {
        Fixture fixture = fixture(CommitBehavior.WRITE_FAILURE, false);
        DocumentFileMetadata beforeMetadata = fixture.model().fileMetadata();
        FileBaseline beforeBaseline = fixture.model().fileBaseline();

        IOException failure = assertThrows(IOException.class, fixture::save);

        assertTrue(failure.getMessage().contains("may contain incomplete data"));
        assertArrayEquals(new byte[]{fixture.expectedBytes()[0], fixture.expectedBytes()[1]},
            Files.readAllBytes(fixture.target()));
        assertSame(beforeMetadata, fixture.model().fileMetadata());
        assertSame(beforeBaseline, fixture.model().fileBaseline());
        assertNoStagedFiles();
    }

    @Test
    void neverDeletesAnExternalReplacementAfterPortableWritingFails() throws IOException {
        Fixture fixture = fixture(CommitBehavior.WRITE_FAILURE_REPLACED, false);
        DocumentFileMetadata beforeMetadata = fixture.model().fileMetadata();
        FileBaseline beforeBaseline = fixture.model().fileBaseline();

        assertThrows(IOException.class, fixture::save);

        assertEquals(EXTERNAL, Files.readString(fixture.target()));
        assertSame(beforeMetadata, fixture.model().fileMetadata());
        assertSame(beforeBaseline, fixture.model().fileBaseline());
        assertNoStagedFiles();
    }

    @Test
    void failedAttributePreservationLeavesOriginalFileAndSaveStateUntouched() throws IOException {
        Path original = tempDir.resolve("existing.txt");
        Files.writeString(original, "original");
        TextModel model = new TextModel(new SimpleLineBuffer());
        model.load(original);
        model.insertText(new Position(0, 0), "edited ");
        DocumentFileMetadata metadata = model.fileMetadata();
        FileBaseline baseline = model.fileBaseline();
        CommitFileSystem fileSystem = new CommitFileSystem(original, CommitBehavior.ATTRIBUTE_FAILURE, false);

        IOException failure = assertThrows(IOException.class, () -> model.save(fileSystem.wrap(original)));

        assertTrue(failure.getMessage().contains("preserve file attributes"));
        assertEquals("original", Files.readString(original));
        assertSame(metadata, model.fileMetadata());
        assertSame(baseline, model.fileBaseline());
        assertNoStagedFiles();
    }

    private Fixture fixture(CommitBehavior behavior, boolean unsupportedAttributes) throws IOException {
        Path original = tempDir.resolve("original.txt");
        Files.writeString(original, "original");
        TextModel model = new TextModel(new SimpleLineBuffer());
        model.load(original);
        model.replaceLines(List.of("edited", "content"));
        DocumentFileMetadata metadata = DocumentFileMetadata.canonical(
            StandardCharsets.UTF_16LE, BomKind.UTF_16_LE, "\r\n", model.lineCount()
        );
        Path rawTarget = tempDir.resolve("new.txt");
        CommitFileSystem fileSystem = new CommitFileSystem(rawTarget, behavior, unsupportedAttributes);
        Path target = fileSystem.wrap(rawTarget);
        byte[] textBytes = CONTENT.replace("\n", "\r\n").getBytes(StandardCharsets.UTF_16LE);
        byte[] expected = new byte[textBytes.length + 2];
        expected[0] = (byte) 0xFF;
        expected[1] = (byte) 0xFE;
        System.arraycopy(textBytes, 0, expected, 2, textBytes.length);
        return new Fixture(model, target, metadata, expected, fileSystem.provider());
    }

    private void assertNoStagedFiles() throws IOException {
        try (var files = Files.list(tempDir)) {
            assertFalse(files.anyMatch(path -> path.getFileName().toString().endsWith(".tmp")));
        }
    }

    private void assumeHardLinks() throws IOException {
        Path source = tempDir.resolve("link-probe-source");
        Path link = tempDir.resolve("link-probe-target");
        Files.writeString(source, "probe");
        try {
            Files.createLink(link, source);
        } catch (IOException | UnsupportedOperationException unsupported) {
            assumeTrue(false, "Hard links are not supported on this filesystem");
        } finally {
            Files.deleteIfExists(link);
            Files.deleteIfExists(source);
        }
    }

    private record Fixture(
        TextModel model, Path target, DocumentFileMetadata metadata,
        byte[] expectedBytes, CommitProvider provider
    ) {
        void save() throws IOException {
            model.save(target, metadata, SaveDisposition.CREATE_NEW);
        }
    }

    private enum CommitBehavior {
        LINK, LINK_RACE, UNSUPPORTED_LINK, LINK_IO_FAILURE,
        OPEN_RACE, WRITE_FAILURE, WRITE_FAILURE_REPLACED, ATTRIBUTE_FAILURE
    }

    /** Delegates real filesystem operations, injecting a second writer at the commit boundary. */
    private static final class CommitProvider extends FileSystemProvider {
        private final CommitFileSystem fileSystem;
        private final Path target;
        private final CommitBehavior behavior;
        private final boolean unsupportedAttributes;
        private byte[] bytesAtLink;
        private boolean sharedInodeAtLink;
        private int exclusiveOpenAttempts;
        private int successfulExclusiveOpens;
        private Set<PosixFilePermission> requestedPermissions = Set.of();

        CommitProvider(CommitFileSystem fileSystem, Path target, CommitBehavior behavior,
                       boolean unsupportedAttributes) {
            this.fileSystem = fileSystem;
            this.target = target;
            this.behavior = behavior;
            this.unsupportedAttributes = unsupportedAttributes;
        }

        @Override
        public void createLink(Path link, Path existing) throws IOException {
            bytesAtLink = Files.readAllBytes(unwrap(existing));
            if (behavior == CommitBehavior.LINK_RACE) {
                Files.writeString(target, EXTERNAL, StandardOpenOption.CREATE_NEW);
            } else if (behavior == CommitBehavior.LINK_IO_FAILURE) {
                throw new IOException("Hard links unavailable on this filesystem");
            } else if (behavior != CommitBehavior.LINK) {
                throw new UnsupportedOperationException("Hard links unavailable");
            }
            Files.createLink(unwrap(link), unwrap(existing));
            sharedInodeAtLink = Files.isSameFile(unwrap(link), unwrap(existing));
        }

        @Override
        public SeekableByteChannel newByteChannel(Path path, Set<? extends OpenOption> options,
                                                 FileAttribute<?>... attributes) throws IOException {
            boolean exclusiveTarget = unwrap(path).equals(target) && options.contains(StandardOpenOption.CREATE_NEW);
            if (exclusiveTarget) {
                exclusiveOpenAttempts++;
            }
            if (unsupportedAttributes && attributes.length > 0) {
                throw new UnsupportedOperationException("POSIX creation attributes unavailable");
            }
            if (exclusiveTarget) {
                for (FileAttribute<?> attribute : attributes) {
                    if (attribute.name().equals("posix:permissions")) {
                        @SuppressWarnings("unchecked")
                        Set<PosixFilePermission> permissions = (Set<PosixFilePermission>) attribute.value();
                        requestedPermissions = permissions;
                    }
                }
                if (behavior == CommitBehavior.OPEN_RACE) {
                    Files.writeString(target, EXTERNAL, StandardOpenOption.CREATE_NEW);
                }
            }
            SeekableByteChannel channel = Files.newByteChannel(unwrap(path), options, attributes);
            if (!exclusiveTarget) {
                return channel;
            }
            successfulExclusiveOpens++;
            return behavior == CommitBehavior.WRITE_FAILURE || behavior == CommitBehavior.WRITE_FAILURE_REPLACED
                ? failingChannel(channel) : channel;
        }

        private SeekableByteChannel failingChannel(SeekableByteChannel channel) {
            return new SeekableByteChannel() {
                private boolean wrotePrefix;

                @Override
                public int write(ByteBuffer bytes) throws IOException {
                    if (wrotePrefix) {
                        if (behavior == CommitBehavior.WRITE_FAILURE_REPLACED) {
                            channel.close();
                            Files.delete(target);
                            Files.writeString(target, EXTERNAL, StandardOpenOption.CREATE_NEW);
                        }
                        throw new IOException("Injected write failure");
                    }
                    ByteBuffer prefix = bytes.duplicate();
                    prefix.limit(prefix.position() + Math.min(2, prefix.remaining()));
                    int written = channel.write(prefix);
                    bytes.position(bytes.position() + written);
                    wrotePrefix = true;
                    return written;
                }

                @Override public int read(ByteBuffer bytes) throws IOException { return channel.read(bytes); }
                @Override public long position() throws IOException { return channel.position(); }
                @Override public SeekableByteChannel position(long value) throws IOException { channel.position(value); return this; }
                @Override public long size() throws IOException { return channel.size(); }
                @Override public SeekableByteChannel truncate(long size) throws IOException { channel.truncate(size); return this; }
                @Override public boolean isOpen() { return channel.isOpen(); }
                @Override public void close() throws IOException { channel.close(); }
            };
        }

        @Override public String getScheme() { return "commit-test"; }
        @Override public FileSystem newFileSystem(URI uri, Map<String, ?> env) { throw new UnsupportedOperationException(); }
        @Override public FileSystem getFileSystem(URI uri) { return fileSystem; }
        @Override public Path getPath(URI uri) { return fileSystem.wrap(Path.of(uri)); }
        @Override public void createDirectory(Path path, FileAttribute<?>... attrs) throws IOException { Files.createDirectory(unwrap(path), attrs); }
        @Override public void delete(Path path) throws IOException { Files.delete(unwrap(path)); }
        @Override public void copy(Path source, Path destination, CopyOption... options) throws IOException { Files.copy(unwrap(source), unwrap(destination), options); }
        @Override public void move(Path source, Path destination, CopyOption... options) throws IOException { Files.move(unwrap(source), unwrap(destination), options); }
        @Override public boolean isSameFile(Path first, Path second) throws IOException { return Files.isSameFile(unwrap(first), unwrap(second)); }
        @Override public boolean isHidden(Path path) throws IOException { return Files.isHidden(unwrap(path)); }
        @Override public FileStore getFileStore(Path path) throws IOException { return Files.getFileStore(unwrap(path)); }
        @Override public void checkAccess(Path path, AccessMode... modes) throws IOException { unwrap(path).getFileSystem().provider().checkAccess(unwrap(path), modes); }
        @Override
        public <V extends FileAttributeView> V getFileAttributeView(Path path, Class<V> type, LinkOption... options) {
            if (behavior == CommitBehavior.ATTRIBUTE_FAILURE && type == UserDefinedFileAttributeView.class) {
                return type.cast(new UserDefinedFileAttributeView() {
                    @Override public String name() { return "user"; }
                    @Override public List<String> list() { return List.of("audit"); }
                    @Override public int size(String name) { return 1; }
                    @Override public int read(String name, ByteBuffer bytes) { bytes.put((byte) 1); return 1; }
                    @Override public int write(String name, ByteBuffer bytes) throws IOException {
                        throw new IOException("Injected extended-attribute write failure");
                    }
                    @Override public void delete(String name) throws IOException {
                        throw new IOException("Unexpected attribute deletion");
                    }
                });
            }
            return Files.getFileAttributeView(unwrap(path), type, options);
        }
        @Override public <A extends BasicFileAttributes> A readAttributes(Path path, Class<A> type, LinkOption... options) throws IOException { return Files.readAttributes(unwrap(path), type, options); }
        @Override public Map<String, Object> readAttributes(Path path, String attrs, LinkOption... options) throws IOException { return Files.readAttributes(unwrap(path), attrs, options); }
        @Override public void setAttribute(Path path, String attr, Object value, LinkOption... options) throws IOException { Files.setAttribute(unwrap(path), attr, value, options); }

        @Override
        public DirectoryStream<Path> newDirectoryStream(Path dir, DirectoryStream.Filter<? super Path> filter)
            throws IOException {
            DirectoryStream<Path> stream = Files.newDirectoryStream(unwrap(dir),
                entry -> filter.accept(fileSystem.wrap(entry)));
            return new DirectoryStream<>() {
                @Override public void close() throws IOException { stream.close(); }
                @Override public Iterator<Path> iterator() {
                    Iterator<Path> entries = stream.iterator();
                    return new Iterator<>() {
                        @Override public boolean hasNext() { return entries.hasNext(); }
                        @Override public Path next() { return fileSystem.wrap(entries.next()); }
                    };
                }
            };
        }
    }

    private static Path unwrap(Path path) {
        return path instanceof CommitPath wrapped ? wrapped.delegate : path;
    }

    private static final class CommitFileSystem extends FileSystem {
        private final FileSystem delegate;
        private final CommitProvider provider;

        CommitFileSystem(Path target, CommitBehavior behavior, boolean unsupportedAttributes) {
            delegate = target.getFileSystem();
            provider = new CommitProvider(this, target, behavior, unsupportedAttributes);
        }

        Path wrap(Path path) { return path == null ? null : new CommitPath(this, path); }
        @Override public CommitProvider provider() { return provider; }
        @Override public void close() {}
        @Override public boolean isOpen() { return delegate.isOpen(); }
        @Override public boolean isReadOnly() { return delegate.isReadOnly(); }
        @Override public String getSeparator() { return delegate.getSeparator(); }
        @Override public Iterable<Path> getRootDirectories() {
            List<Path> roots = new ArrayList<>();
            delegate.getRootDirectories().forEach(path -> roots.add(wrap(path)));
            return roots;
        }
        @Override public Iterable<FileStore> getFileStores() { return delegate.getFileStores(); }
        @Override public Set<String> supportedFileAttributeViews() { return delegate.supportedFileAttributeViews(); }
        @Override public Path getPath(String first, String... more) { return wrap(delegate.getPath(first, more)); }
        @Override public PathMatcher getPathMatcher(String syntaxAndPattern) {
            PathMatcher matcher = delegate.getPathMatcher(syntaxAndPattern);
            return path -> matcher.matches(unwrap(path));
        }
        @Override public UserPrincipalLookupService getUserPrincipalLookupService() { return delegate.getUserPrincipalLookupService(); }
        @Override public WatchService newWatchService() throws IOException { return delegate.newWatchService(); }
    }

    private static final class CommitPath implements Path {
        private final CommitFileSystem fileSystem;
        private final Path delegate;

        CommitPath(CommitFileSystem fileSystem, Path delegate) {
            this.fileSystem = fileSystem;
            this.delegate = delegate;
        }

        @Override public FileSystem getFileSystem() { return fileSystem; }
        @Override public boolean isAbsolute() { return delegate.isAbsolute(); }
        @Override public Path getRoot() { return fileSystem.wrap(delegate.getRoot()); }
        @Override public Path getFileName() { return fileSystem.wrap(delegate.getFileName()); }
        @Override public Path getParent() { return fileSystem.wrap(delegate.getParent()); }
        @Override public int getNameCount() { return delegate.getNameCount(); }
        @Override public Path getName(int index) { return fileSystem.wrap(delegate.getName(index)); }
        @Override public Path subpath(int begin, int end) { return fileSystem.wrap(delegate.subpath(begin, end)); }
        @Override public boolean startsWith(Path other) { return delegate.startsWith(unwrap(other)); }
        @Override public boolean startsWith(String other) { return delegate.startsWith(other); }
        @Override public boolean endsWith(Path other) { return delegate.endsWith(unwrap(other)); }
        @Override public boolean endsWith(String other) { return delegate.endsWith(other); }
        @Override public Path normalize() { return fileSystem.wrap(delegate.normalize()); }
        @Override public Path resolve(Path other) { return fileSystem.wrap(delegate.resolve(unwrap(other))); }
        @Override public Path resolve(String other) { return fileSystem.wrap(delegate.resolve(other)); }
        @Override public Path resolveSibling(Path other) { return fileSystem.wrap(delegate.resolveSibling(unwrap(other))); }
        @Override public Path resolveSibling(String other) { return fileSystem.wrap(delegate.resolveSibling(other)); }
        @Override public Path relativize(Path other) { return fileSystem.wrap(delegate.relativize(unwrap(other))); }
        @Override public URI toUri() { return delegate.toUri(); }
        @Override public Path toAbsolutePath() { return fileSystem.wrap(delegate.toAbsolutePath()); }
        @Override public Path toRealPath(LinkOption... options) throws IOException { return fileSystem.wrap(delegate.toRealPath(options)); }
        @Override public WatchKey register(WatchService watcher, WatchEvent.Kind<?>[] events, WatchEvent.Modifier... modifiers) throws IOException { return delegate.register(watcher, events, modifiers); }
        @Override public int compareTo(Path other) { return delegate.compareTo(unwrap(other)); }
        @Override public int hashCode() { return 31 * System.identityHashCode(fileSystem) + delegate.hashCode(); }
        @Override public String toString() { return delegate.toString(); }
        @Override public boolean equals(Object other) {
            return other instanceof CommitPath path && fileSystem == path.fileSystem && delegate.equals(path.delegate);
        }
    }
}
