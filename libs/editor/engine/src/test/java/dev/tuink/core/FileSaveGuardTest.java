package dev.tuink.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

class FileSaveGuardTest {
    @TempDir Path directory;

    @Test
    void aliasesShareOnePersistentLockAcrossAtomicReplacement() throws IOException {
        assumeTrue(directory.getFileSystem().supportedFileAttributeViews().contains("posix"));
        Path documents = Files.createDirectory(directory.resolve("documents"));
        Path target = Files.writeString(documents.resolve("document.txt"), "before");
        Path alias = Files.createSymbolicLink(documents.resolve("alias.txt"), target.getFileName());
        Path namespace = directory.resolve("locks");
        Path lockPath = FileSaveGuard.lockPath(target, namespace);
        Object lockIdentity;
        try (FileSaveGuard guard = FileSaveGuard.acquire(target, namespace)) {
            assertNotNull(guard);
            lockIdentity = Files.readAttributes(lockPath, BasicFileAttributes.class).fileKey();
            assertEquals(lockPath, FileSaveGuard.lockPath(alias, namespace));
            assertThrows(IOException.class, () -> FileSaveGuard.acquire(alias, namespace));
            Path staged = Files.writeString(documents.resolve("staged"), "after");
            Files.move(staged, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            assertThrows(IOException.class, () -> FileSaveGuard.acquire(alias, namespace));
        }
        try (FileSaveGuard guard = FileSaveGuard.acquire(alias, namespace)) {
            assertNotNull(guard);
            assertEquals(lockIdentity, Files.readAttributes(lockPath, BasicFileAttributes.class).fileKey());
        }
        assertEquals(PosixFilePermissions.fromString("rwx------"), Files.getPosixFilePermissions(namespace));
        assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(lockPath));
        try (var files = Files.list(documents)) {
            assertEquals(2, files.count(), "Coordination must not create sidecars beside documents");
        }
    }

    @Test
    void anAbsentEntryKeepsItsIdentityAfterCreation() throws IOException {
        Path target = directory.resolve("new.txt");
        Path namespace = directory.resolve("locks");
        Path lockPath = FileSaveGuard.lockPath(target, namespace);
        try (FileSaveGuard guard = FileSaveGuard.acquire(target, namespace)) {
            assertNotNull(guard);
            Files.writeString(target, "created");
            assertEquals(lockPath, FileSaveGuard.lockPath(target, namespace));
            assertThrows(IOException.class, () -> FileSaveGuard.acquire(target, namespace));
        }
    }

    @Test
    void theFirstSaveCanCreateItsNamespaceInsideANewAbsoluteUserHome() throws IOException {
        Path home = directory.resolve("new-home");
        Path target = directory.resolve("new-document.txt");
        String previous = System.getProperty("user.home");
        assertFalse(Files.exists(home));
        try {
            System.setProperty("user.home", home.toString());
            try (FileSaveGuard guard = FileSaveGuard.acquire(target)) {
                assertNotNull(guard);
                try (var files = Files.list(home.resolve(".local/state/tuink/save-locks-v1"))) {
                    assertEquals(supportedLockCount(target), files.count());
                }
                assertFalse(Files.exists(target));
            }
        } finally {
            if (previous == null) System.clearProperty("user.home");
            else System.setProperty("user.home", previous);
        }
    }

    @Test
    void hardlinkedDocumentsRemainIndependentDirectoryEntries() throws IOException {
        assumeTrue(directory.getFileSystem().supportedFileAttributeViews().contains("posix"));
        Path first = Files.writeString(directory.resolve("first.txt"), "same inode");
        Path second = Files.createLink(directory.resolve("second.txt"), first);
        Path namespace = directory.resolve("locks");
        assertNotEquals(FileSaveGuard.lockPath(first, namespace), FileSaveGuard.lockPath(second, namespace));
        try (FileSaveGuard firstGuard = FileSaveGuard.acquire(first, namespace);
             FileSaveGuard secondGuard = FileSaveGuard.acquire(second, namespace)) {
            assertNotNull(firstGuard);
            assertNotNull(secondGuard);
        }
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void unixFilenameBytesNeverCollideWithTheirLossyDisplayNames() throws IOException {
        Path raw = Path.of(URI.create(directory.toUri().toASCIIString() + "legacy-%FF.txt"));
        Path display = directory.resolve("legacy-\uFFFD.txt");
        Files.writeString(raw, "raw");
        Files.writeString(display, "display");
        Path namespace = directory.resolve("locks");
        assertEquals(raw.toString(), display.toString());
        assertNotEquals(FileSaveGuard.lockPath(raw, namespace), FileSaveGuard.lockPath(display, namespace));
        try (FileSaveGuard rawGuard = FileSaveGuard.acquire(raw, namespace);
             FileSaveGuard displayGuard = FileSaveGuard.acquire(display, namespace)) {
            assertNotNull(rawGuard);
            assertNotNull(displayGuard);
        }
    }

    @Test
    void unsafeSidecarsAndNamespaceLinksFailClosed() throws IOException {
        assumeTrue(directory.getFileSystem().supportedFileAttributeViews().contains("posix"));
        for (String kind : List.of("symlink", "hardlink", "directory")) {
            Path target = Files.writeString(directory.resolve(kind + ".txt"), "preserve target");
            Path namespace = directory.resolve(kind + "-locks");
            Path sidecar = FileSaveGuard.lockPath(target, namespace);
            Path unrelated = Files.writeString(directory.resolve(kind + "-unrelated"), "preserve unrelated");
            if (kind.equals("symlink")) Files.createSymbolicLink(sidecar, unrelated);
            else if (kind.equals("hardlink")) Files.createLink(sidecar, unrelated);
            else Files.createDirectory(sidecar);
            assertThrows(IOException.class, () -> FileSaveGuard.acquire(target, namespace));
            assertEquals("preserve target", Files.readString(target));
            assertEquals("preserve unrelated", Files.readString(unrelated));
        }
        Path namespaceAlias = Files.createSymbolicLink(directory.resolve("namespace-alias"), directory);
        assertThrows(IOException.class, () -> FileSaveGuard.acquire(directory.resolve("new.txt"), namespaceAlias));
        assertFalse(Files.exists(directory.resolve("new.txt")));
    }

    @Test
    void unsupportedLocksReleaseTheirLocalClaimBeforeRetry() throws IOException {
        Path target = directory.resolve("new.txt");
        Path namespace = directory.resolve("locks");
        assertThrows(IOException.class, () -> FileSaveGuard.acquire(target, namespace, (path, options, attrs) -> {
            throw new UnsupportedOperationException("Injected unsupported locks");
        }));
        try (FileSaveGuard guard = FileSaveGuard.acquire(target, namespace)) {
            assertNotNull(guard);
        }
    }

    @Test
    void failedChannelCleanupReleasesTheLocalClaimAndRetainsTheSidecar() throws IOException {
        Path target = directory.resolve("new.txt");
        Path namespace = directory.resolve("locks");
        FileSaveGuard guard = FileSaveGuard.acquire(target, namespace,
            (path, options, attrs) -> new ClosingFailureChannel(FileChannel.open(path, options, attrs)));
        assertThrows(IOException.class, guard::close);
        try (FileSaveGuard retry = FileSaveGuard.acquire(target, namespace)) {
            assertNotNull(retry);
        }
        try (var files = Files.list(namespace)) {
            assertEquals(supportedLockCount(target), files.count());
        }
    }

    @Test
    void everyLockIsAcquiredInDeterministicOrderAndReleased() throws IOException {
        Path target = Files.writeString(directory.resolve("document.txt"), "original");
        Path namespace = directory.resolve("locks");
        List<Path> opened = new ArrayList<>();
        List<FileChannel> channels = new ArrayList<>();
        try (FileSaveGuard guard = FileSaveGuard.acquire(target, namespace, (path, options, attrs) -> {
            opened.add(path);
            FileChannel channel = FileChannel.open(path, options, attrs);
            channels.add(channel);
            return channel;
        })) {
            assertNotNull(guard);
            assertEquals(supportedLockCount(target), channels.size());
            assertTrue(opened.contains(FileSaveGuard.lockPath(target, namespace)), "Keep the legacy URI lock");
            assertEquals(opened.stream().sorted(Comparator.comparing(path -> path.getFileName().toString())).toList(), opened);
            for (FileChannel channel : channels) {
                assertThrows(OverlappingFileLockException.class, channel::tryLock);
            }
        }
        for (FileChannel channel : channels) assertFalse(channel.isOpen());
        try (FileSaveGuard retry = FileSaveGuard.acquire(target, namespace)) { assertNotNull(retry); }
    }

    @Test
    void aFailedSecondOpenRollsBackEveryChannelAndLocalClaim() throws IOException {
        assumeTrue(directory.getFileSystem().supportedFileAttributeViews().contains("unix"));
        Path target = Files.writeString(directory.resolve("document.txt"), "original");
        Path namespace = directory.resolve("locks");
        List<FileChannel> channels = new ArrayList<>();
        AtomicInteger opens = new AtomicInteger();

        assertThrows(IOException.class, () -> FileSaveGuard.acquire(target, namespace, (path, options, attrs) -> {
            if (opens.incrementAndGet() == 2) throw new IOException("Second lock descriptor unavailable");
            FileChannel channel = FileChannel.open(path, options, attrs);
            channels.add(channel);
            return channel;
        }));

        assertEquals(2, opens.get());
        assertEquals(1, channels.size());
        assertFalse(channels.get(0).isOpen());
        try (FileSaveGuard retry = FileSaveGuard.acquire(target, namespace)) { assertNotNull(retry); }
        assertEquals("original", Files.readString(target));
    }

    @Test
    void bothCleanupFailuresAreAttemptedAndTheSecondIsSuppressed() throws IOException {
        assumeTrue(directory.getFileSystem().supportedFileAttributeViews().contains("unix"));
        Path target = Files.writeString(directory.resolve("document.txt"), "original");
        Path namespace = directory.resolve("locks");
        List<FileChannel> channels = new ArrayList<>();
        FileSaveGuard guard = FileSaveGuard.acquire(target, namespace, (path, options, attrs) -> {
            FileChannel channel = new ClosingFailureChannel(FileChannel.open(path, options, attrs));
            channels.add(channel);
            return channel;
        });

        IOException failure = assertThrows(IOException.class, guard::close);

        assertEquals(2, channels.size());
        assertEquals(1, failure.getSuppressed().length);
        for (FileChannel channel : channels) assertFalse(channel.isOpen());
        guard.close();
        try (FileSaveGuard retry = FileSaveGuard.acquire(target, namespace)) { assertNotNull(retry); }
    }

    @Test
    void aRenamedDirectoryEntryRetainsItsCommonLockBeforeAnyNewDescriptorOpens() throws IOException {
        assumeTrue(directory.getFileSystem().supportedFileAttributeViews().contains("unix"));
        Path parent = Files.createDirectory(directory.resolve("documents"));
        Path target = Files.writeString(parent.resolve("document.txt"), "original");
        Path namespace = directory.resolve("locks");
        Path legacy = FileSaveGuard.lockPath(target, namespace);
        Path moved = directory.resolve("moved-documents");
        AtomicInteger newDescriptors = new AtomicInteger();
        try (FileSaveGuard guard = FileSaveGuard.acquire(target, namespace)) {
            Files.move(parent, moved);
            Path renamed = moved.resolve(target.getFileName());
            assertNotEquals(legacy, FileSaveGuard.lockPath(renamed, namespace));
            assertThrows(IOException.class, () -> FileSaveGuard.acquire(renamed, namespace, (path, options, attrs) -> {
                newDescriptors.incrementAndGet();
                return FileChannel.open(path, options, attrs);
            }));
            assertEquals(0, newDescriptors.get(), "Reject the common entry before opening any of its lock descriptors");
        }
        try (FileSaveGuard retry = FileSaveGuard.acquire(moved.resolve(target.getFileName()), namespace)) { assertNotNull(retry); }
    }

    @Test
    void aRenamedLockNamespaceCannotOpenAnotherDescriptorOfAHeldLock() throws IOException {
        assumeTrue(directory.getFileSystem().supportedFileAttributeViews().contains("unix"));
        Path target = Files.writeString(directory.resolve("document.txt"), "original");
        Path namespace = directory.resolve("locks");
        Path moved = directory.resolve("moved-locks");
        AtomicInteger newDescriptors = new AtomicInteger();
        try (FileSaveGuard guard = FileSaveGuard.acquire(target, namespace)) {
            Files.move(namespace, moved);
            assertThrows(IOException.class, () -> FileSaveGuard.acquire(target, moved, (path, options, attrs) -> {
                newDescriptors.incrementAndGet();
                return FileChannel.open(path, options, attrs);
            }));
            assertEquals(0, newDescriptors.get(), "A second open/close could release this process's first POSIX lock");
        }
        try (FileSaveGuard retry = FileSaveGuard.acquire(target, moved)) { assertNotNull(retry); }
    }

    private static int supportedLockCount(Path target) {
        return target.getFileSystem().supportedFileAttributeViews().contains("unix") ? 2 : 1;
    }

    private static final class ClosingFailureChannel extends FileChannel {
        private final FileChannel channel;
        ClosingFailureChannel(FileChannel channel) { this.channel = channel; }
        @Override public int read(ByteBuffer buffer) throws IOException { return channel.read(buffer); }
        @Override public long read(ByteBuffer[] buffers, int offset, int length) throws IOException {
            return channel.read(buffers, offset, length);
        }
        @Override public int read(ByteBuffer buffer, long position) throws IOException { return channel.read(buffer, position); }
        @Override public int write(ByteBuffer buffer) throws IOException { return channel.write(buffer); }
        @Override public long write(ByteBuffer[] buffers, int offset, int length) throws IOException {
            return channel.write(buffers, offset, length);
        }
        @Override public int write(ByteBuffer buffer, long position) throws IOException { return channel.write(buffer, position); }
        @Override public long position() throws IOException { return channel.position(); }
        @Override public FileChannel position(long position) throws IOException { channel.position(position); return this; }
        @Override public long size() throws IOException { return channel.size(); }
        @Override public FileChannel truncate(long size) throws IOException { channel.truncate(size); return this; }
        @Override public void force(boolean metadata) throws IOException { channel.force(metadata); }
        @Override public long transferTo(long position, long count, WritableByteChannel target) throws IOException {
            return channel.transferTo(position, count, target);
        }
        @Override public long transferFrom(ReadableByteChannel source, long position, long count) throws IOException {
            return channel.transferFrom(source, position, count);
        }
        @Override public MappedByteBuffer map(MapMode mode, long position, long size) throws IOException {
            return channel.map(mode, position, size);
        }
        @Override public FileLock lock(long position, long size, boolean shared) throws IOException {
            return channel.lock(position, size, shared);
        }
        @Override public FileLock tryLock(long position, long size, boolean shared) throws IOException {
            return channel.tryLock(position, size, shared);
        }
        @Override protected void implCloseChannel() throws IOException {
            channel.close();
            throw new IOException("Injected lock close failure");
        }
    }
}
