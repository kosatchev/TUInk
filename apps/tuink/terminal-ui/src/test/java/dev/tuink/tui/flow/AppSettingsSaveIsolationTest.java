package dev.tuink.tui.flow;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tuink.core.GutterDiffMode;
import dev.tuink.core.TabInsertMode;
import dev.tuink.tui.style.UiStyles;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.AccessMode;
import java.nio.file.CopyOption;
import java.nio.file.DirectoryStream;
import java.nio.file.FileStore;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.WatchService;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.FileAttributeView;
import java.nio.file.attribute.UserPrincipalLookupService;
import java.nio.file.spi.FileSystemProvider;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AppSettingsSaveIsolationTest {
    private static final int TIMEOUT_SECONDS = 10;

    @TempDir
    Path directory;

    @Test
    void overlappingSavesCommitIndependentWholeSnapshots() throws Exception {
        Path target = directory.resolve("tuink.properties");
        AppSettings firstSettings = AppSettings.defaults();
        AppSettings secondSettings = new AppSettings(
            false, true, false, true, false, false, false, false, false,
            false, false, false, false, false, false,
            GutterDiffMode.STABLE, 2, TabInsertMode.ALWAYS_SPACES_2, UiStyles.Theme.LIGHT
        );
        SavingFileSystem fileSystem = new SavingFileSystem(target.getFileSystem(), Mode.PAUSE_FIRST_MOVE);
        Path wrappedTarget = fileSystem.wrap(target);

        try (var executor = Executors.newFixedThreadPool(2)) {
            try {
                var first = executor.submit(() -> {
                    AppSettingsStore.save(wrappedTarget, firstSettings);
                    return null;
                });
                assertTrue(fileSystem.provider.firstMoveReady.await(TIMEOUT_SECONDS, SECONDS));

                var second = executor.submit(() -> {
                    AppSettingsStore.save(wrappedTarget, secondSettings);
                    return null;
                });
                second.get(TIMEOUT_SECONDS, SECONDS);
                assertEquals(secondSettings, AppSettingsStore.load(target));

                fileSystem.provider.releaseFirstMove.countDown();
                first.get(TIMEOUT_SECONDS, SECONDS);
                assertEquals(firstSettings, AppSettingsStore.load(target));
                assertOnlyFiles("tuink.properties");
            } finally {
                fileSystem.provider.releaseFirstMove.countDown();
                executor.shutdownNow();
            }
        }
    }

    @Test
    void preservesAnExistingTemporaryFileOwnedByAnotherWriter() throws IOException {
        Path target = directory.resolve("tuink.properties");
        Path foreignTemporary = directory.resolve("tuink.properties.tmp");
        Files.writeString(foreignTemporary, "another writer's pending snapshot\n");

        AppSettingsStore.save(target, AppSettings.defaults());

        assertEquals(AppSettings.defaults(), AppSettingsStore.load(target));
        assertEquals("another writer's pending snapshot\n", Files.readString(foreignTemporary));
        assertOnlyFiles("tuink.properties", "tuink.properties.tmp");
    }

    @Test
    void failedWritePreservesCommittedSettingsAndRemovesItsOwnTemporaryFile() throws IOException {
        Path target = directory.resolve("tuink.properties");
        AppSettings original = AppSettings.defaults();
        AppSettingsStore.save(target, original);
        String committedBytes = Files.readString(target);
        SavingFileSystem fileSystem = new SavingFileSystem(target.getFileSystem(), Mode.FAIL_WRITE);

        IOException failure = assertThrows(
            IOException.class,
            () -> AppSettingsStore.save(fileSystem.wrap(target), original)
        );

        assertEquals("Injected settings write failure", failure.getMessage());
        assertEquals(committedBytes, Files.readString(target));
        assertEquals(original, AppSettingsStore.load(target));
        assertOnlyFiles("tuink.properties");
    }

    private void assertOnlyFiles(String... expected) throws IOException {
        try (var files = Files.list(directory)) {
            assertEquals(Set.of(expected), files.map(path -> path.getFileName().toString()).collect(Collectors.toSet()));
        }
    }

    private enum Mode {
        PAUSE_FIRST_MOVE,
        FAIL_WRITE
    }

    /** A private forwarding filesystem that intercepts only the save's I/O boundaries. */
    private static final class SavingFileSystem extends FileSystem {
        private final FileSystem delegate;
        private final SavingProvider provider;

        SavingFileSystem(FileSystem delegate, Mode mode) {
            this.delegate = delegate;
            provider = new SavingProvider(this, mode);
        }

        Path wrap(Path path) {
            return path == null ? null : (Path) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[] {Path.class}, new SavingPath(this, path)
            );
        }

        @Override public SavingProvider provider() { return provider; }
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

    private static final class SavingProvider extends FileSystemProvider {
        private final SavingFileSystem fileSystem;
        private final FileSystemProvider delegate;
        private final Mode mode;
        private final AtomicInteger moveCount = new AtomicInteger();
        private final CountDownLatch firstMoveReady = new CountDownLatch(1);
        private final CountDownLatch releaseFirstMove = new CountDownLatch(1);

        SavingProvider(SavingFileSystem fileSystem, Mode mode) {
            this.fileSystem = fileSystem;
            delegate = fileSystem.delegate.provider();
            this.mode = mode;
        }

        @Override
        public void move(Path source, Path target, CopyOption... options) throws IOException {
            if (mode == Mode.PAUSE_FIRST_MOVE && moveCount.incrementAndGet() == 1) {
                firstMoveReady.countDown();
                try {
                    if (!releaseFirstMove.await(TIMEOUT_SECONDS, SECONDS)) {
                        throw new IOException("Timed out waiting to commit the first settings snapshot");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Interrupted before committing settings", e);
                }
            }
            delegate.move(unwrap(source), unwrap(target), options);
        }

        @Override
        public OutputStream newOutputStream(Path path, OpenOption... options) throws IOException {
            OutputStream output = delegate.newOutputStream(unwrap(path), options);
            if (mode != Mode.FAIL_WRITE) {
                return output;
            }
            return new FilterOutputStream(output) {
                @Override public void write(int value) throws IOException { throw failure(); }
                @Override public void write(byte[] bytes, int offset, int length) throws IOException { throw failure(); }
                private IOException failure() { return new IOException("Injected settings write failure"); }
            };
        }

        @Override public String getScheme() { return delegate.getScheme(); }
        @Override public FileSystem newFileSystem(URI uri, Map<String, ?> env) { throw new UnsupportedOperationException(); }
        @Override public FileSystem getFileSystem(URI uri) { return fileSystem; }
        @Override public Path getPath(URI uri) { return fileSystem.wrap(delegate.getPath(uri)); }
        @Override public SeekableByteChannel newByteChannel(Path path, Set<? extends OpenOption> options, FileAttribute<?>... attrs) throws IOException { return delegate.newByteChannel(unwrap(path), options, attrs); }
        @Override public void createDirectory(Path path, FileAttribute<?>... attrs) throws IOException { delegate.createDirectory(unwrap(path), attrs); }
        @Override public void delete(Path path) throws IOException { delegate.delete(unwrap(path)); }
        @Override public void copy(Path source, Path target, CopyOption... options) throws IOException { delegate.copy(unwrap(source), unwrap(target), options); }
        @Override public boolean isSameFile(Path first, Path second) throws IOException { return delegate.isSameFile(unwrap(first), unwrap(second)); }
        @Override public boolean isHidden(Path path) throws IOException { return delegate.isHidden(unwrap(path)); }
        @Override public FileStore getFileStore(Path path) throws IOException { return delegate.getFileStore(unwrap(path)); }
        @Override public void checkAccess(Path path, AccessMode... modes) throws IOException { delegate.checkAccess(unwrap(path), modes); }
        @Override public <V extends FileAttributeView> V getFileAttributeView(Path path, Class<V> type, LinkOption... options) { return delegate.getFileAttributeView(unwrap(path), type, options); }
        @Override public <A extends BasicFileAttributes> A readAttributes(Path path, Class<A> type, LinkOption... options) throws IOException { return delegate.readAttributes(unwrap(path), type, options); }
        @Override public Map<String, Object> readAttributes(Path path, String attrs, LinkOption... options) throws IOException { return delegate.readAttributes(unwrap(path), attrs, options); }
        @Override public void setAttribute(Path path, String attr, Object value, LinkOption... options) throws IOException { delegate.setAttribute(unwrap(path), attr, value, options); }
        @Override public DirectoryStream<Path> newDirectoryStream(Path dir, DirectoryStream.Filter<? super Path> filter) throws IOException {
            DirectoryStream<Path> stream = delegate.newDirectoryStream(unwrap(dir), path -> filter.accept(fileSystem.wrap(path)));
            return new DirectoryStream<>() {
                @Override public void close() throws IOException { stream.close(); }
                @Override public Iterator<Path> iterator() { return wrapIterator(fileSystem, stream.iterator()); }
            };
        }
    }

    private record SavingPath(SavingFileSystem fileSystem, Path delegate) implements InvocationHandler {
        @Override
        public Object invoke(Object proxy, Method method, Object[] arguments) throws Throwable {
            if (method.getName().equals("getFileSystem")) {
                return fileSystem;
            }
            if (method.getName().equals("equals")) {
                return arguments[0] instanceof Path other
                    && Proxy.isProxyClass(other.getClass())
                    && Proxy.getInvocationHandler(other) instanceof SavingPath wrapped
                    && fileSystem == wrapped.fileSystem
                    && delegate.equals(wrapped.delegate);
            }
            if (method.getName().equals("hashCode")) {
                return 31 * System.identityHashCode(fileSystem) + delegate.hashCode();
            }
            if (method.getName().equals("iterator")) {
                return wrapIterator(fileSystem, delegate.iterator());
            }
            Object[] unwrapped = arguments == null ? null : arguments.clone();
            if (unwrapped != null) {
                for (int index = 0; index < unwrapped.length; index++) {
                    if (unwrapped[index] instanceof Path path) {
                        unwrapped[index] = unwrap(path);
                    }
                }
            }
            try {
                Object result = method.invoke(delegate, unwrapped);
                return result instanceof Path path ? fileSystem.wrap(path) : result;
            } catch (InvocationTargetException e) {
                throw e.getCause();
            }
        }
    }

    private static Iterator<Path> wrapIterator(SavingFileSystem fileSystem, Iterator<Path> iterator) {
        return new Iterator<>() {
            @Override public boolean hasNext() { return iterator.hasNext(); }
            @Override public Path next() { return fileSystem.wrap(iterator.next()); }
        };
    }

    private static Path unwrap(Path path) {
        if (Proxy.isProxyClass(path.getClass()) && Proxy.getInvocationHandler(path) instanceof SavingPath wrapped) {
            return wrapped.delegate;
        }
        return path;
    }
}
