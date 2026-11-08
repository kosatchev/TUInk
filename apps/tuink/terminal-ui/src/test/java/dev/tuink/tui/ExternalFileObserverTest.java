package dev.tuink.tui;

import static org.junit.jupiter.api.Assertions.*;

import dev.tuink.editor.document.DocumentId;
import dev.tuink.editor.document.DocumentSnapshot;
import dev.tuink.editor.session.document.DocumentIoCoordinator;
import dev.tuink.editor.session.document.FrozenDocumentSession;
import dev.tuink.editor.storage.BomKind;
import dev.tuink.editor.storage.DocumentLoad;
import dev.tuink.editor.storage.DocumentOpenMode;
import dev.tuink.editor.storage.FrozenDocumentSource;
import dev.tuink.editor.storage.ResourceBinding;
import dev.tuink.editor.storage.SaveDisposition;
import dev.tuink.storage.jvm.JvmDocumentStorage;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.util.ArrayDeque;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class ExternalFileObserverTest {
    @TempDir Path directory;

    @Test
    void periodicMetadataChecksSuppressIdenticalBytesAndTouchButPublishActualChangesWithoutMutatingTheOwner() throws Exception {
        Path file = Files.writeString(directory.resolve("source.txt"), "original\n");
        var subject = subject(file, DocumentOpenMode.UTF_8);
        var original = subject.source();
        var queue = new QueueExecutor();
        var clock = new AtomicLong(1_000_000_000L);
        var loads = new AtomicInteger();
        try (var observer = observer(queue, clock, (resource, mode) -> {
            assertFalse(subject.isOwnerThread(), "Decoding must run on the detached worker");
            loads.incrementAndGet();
            return resource.storage().load(resource, mode, false);
        })) {
            observer.schedule(subject);
            assertEquals(0, loads.get());
            assertTrue(observer.poll().isEmpty());
            queue.runNext();
            assertEquals(ExternalFileObserver.Kind.UNCHANGED, only(observer).kind());
            assertEquals(1, loads.get());
            tick(observer, subject, queue, clock);
            assertEquals(ExternalFileObserver.Kind.UNCHANGED, only(observer).kind());
            assertEquals(1, loads.get(), "Unchanged attributes use the fast check");

            Files.setLastModifiedTime(file, FileTime.fromMillis(Files.getLastModifiedTime(file).toMillis() + 2000));
            tick(observer, subject, queue, clock);
            assertEquals(ExternalFileObserver.Kind.UNCHANGED, only(observer).kind());
            assertEquals(1, loads.get(), "A touch hashes identical bytes without decoding another document");
            Path replacement = Files.writeString(directory.resolve("replacement"), "original\n");
            Files.move(replacement, file, StandardCopyOption.REPLACE_EXISTING);
            tick(observer, subject, queue, clock);
            assertEquals(ExternalFileObserver.Kind.UNCHANGED, only(observer).kind());
            assertEquals(1, loads.get(), "A byte-identical replacement does not materialize a decoded copy");

            Files.writeString(file, "external replacement text\r\n");
            tick(observer, subject, queue, clock);
            var changed = only(observer);
            assertEquals(ExternalFileObserver.Kind.CHANGED, changed.kind());
            assertEquals(List.of("external replacement text", ""), changed.loaded().lines());
            assertEquals(List.of("\r\n"), changed.loaded().metadata().lineSeparators());
            assertSame(original, subject.source(), "Observation cannot reload, rebind or dirty an owner");
        }
    }

    @Test
    void fullContentFallbackDetectsInPlaceEditsEvenWhenSizeAndTimestampsAreRestored() throws Exception {
        Path file = Files.writeString(directory.resolve("same-attributes.txt"), "AAAA");
        var subject = subject(file, DocumentOpenMode.UTF_8);
        var queue = new QueueExecutor();
        var clock = new AtomicLong(1_000_000_000L);
        var loads = new AtomicInteger();
        try (var observer = observer(queue, clock, (resource, mode) -> {
            loads.incrementAndGet(); return resource.storage().load(resource, mode, false);
        })) {
            observer.schedule(subject); queue.runNext(); only(observer);
            FileTime time = Files.getLastModifiedTime(file);
            Files.writeString(file, "BBBB");
            Files.setLastModifiedTime(file, time);
            for (int second = 1; second < 5; second++) {
                tick(observer, subject, queue, clock);
                assertEquals(ExternalFileObserver.Kind.UNCHANGED, only(observer).kind());
            }
            assertEquals(1, loads.get());
            tick(observer, subject, queue, clock);
            assertEquals(List.of("BBBB"), only(observer).loaded().lines());
            assertEquals(2, loads.get());
        }
    }

    @Test
    void missingDeniedUnknownAndKnownChangedButUndecodableAreDifferentOutcomes() throws Exception {
        Path file = Files.writeString(directory.resolve("errors.txt"), "original");
        var subject = subject(file, DocumentOpenMode.UTF_8);
        var queue = new QueueExecutor();
        var clock = new AtomicLong();
        for (var error : List.of(new AccessDeniedException(file.toString()), new IOException("unavailable"))) {
            try (var observer = observer(queue, clock, (resource, mode) -> { throw error; })) {
                observer.schedule(subject); queue.runNext();
                assertEquals(error instanceof AccessDeniedException ? ExternalFileObserver.Kind.DENIED
                    : ExternalFileObserver.Kind.UNKNOWN, only(observer).kind());
            }
        }
        Files.write(file, new byte[] {(byte) 0xC3, 0x28});
        try (var observer = observer(queue, clock, (resource, mode) -> resource.storage().load(resource, mode, false))) {
            observer.schedule(subject); queue.runNext();
            var failed = only(observer);
            assertEquals(ExternalFileObserver.Kind.CHANGED, failed.kind());
            assertNull(failed.loaded());
            assertNotNull(failed.version());
            assertNotNull(failed.failure());
            assertEquals(List.of("original"), subject.source().text().lines());
            Files.delete(file);
            observer.schedule(subject); queue.runNext();
            assertEquals(ExternalFileObserver.Kind.MISSING, only(observer).kind());
        }
    }

    @Test
    void aFileChangingDuringDecodeDoesNotPublishAMixedSnapshot() throws Exception {
        Path file = Files.writeString(directory.resolve("unstable.txt"), "original");
        var subject = subject(file, DocumentOpenMode.UTF_8);
        Files.writeString(file, "first external version");
        var queue = new QueueExecutor();
        try (var observer = observer(queue, new AtomicLong(), (resource, mode) -> {
            var loaded = resource.storage().load(resource, mode, false);
            Files.writeString(file, "second external version, changed while decoding");
            return loaded;
        })) {
            observer.schedule(subject); queue.runNext();
            assertEquals(ExternalFileObserver.Kind.UNKNOWN, only(observer).kind());
        }
    }

    @Test
    void repeatedRequestsAreCoalescedAndLateResultsCannotCrossRelocationOrStop() throws Exception {
        Path first = Files.writeString(directory.resolve("first.txt"), "first");
        Path second = Files.writeString(directory.resolve("second.txt"), "second");
        Path third = Files.writeString(directory.resolve("third.txt"), "third");
        var original = subject(first, DocumentOpenMode.UTF_8);
        var middle = subject(second, DocumentOpenMode.UTF_8);
        var latest = subject(third, DocumentOpenMode.UTF_8);
        var queue = new QueueExecutor();
        try (var observer = observer(queue, new AtomicLong(), (resource, mode) -> resource.storage().load(resource, mode, false))) {
            observer.schedule(original);
            for (int index = 0; index < 1000; index++) observer.schedule(middle);
            observer.schedule(latest);
            assertEquals(1, queue.tasks.size());
            queue.runNext();
            assertSame(original, only(observer).subject());
            assertEquals(1, queue.tasks.size());
            queue.runNext();
            assertSame(latest, only(observer).subject());

            observer.schedule(original); queue.runNext();
            ResourceBinding before = original.binding();
            assertTrue(original.relocateResource(before, ResourceBinding.unbound()));
            assertTrue(original.relocateResource(ResourceBinding.unbound(), before));
            assertTrue(observer.poll().isEmpty(), "Away and back still changes the owner generation");
            observer.schedule(original); queue.runNext(); original.close();
            assertTrue(observer.poll().isEmpty());
        }
    }

    @Test
    void acceptingAnOwnSaveDiscardsItsOldObservationAndWindowIoDefersPublication() throws Exception {
        Path file = Files.writeString(directory.resolve("own-save.txt"), "disk");
        var original = subject(file, DocumentOpenMode.UTF_8);
        var source = original.source();
        var subject = FrozenDocumentSession.create(new FrozenDocumentSource(
            new DocumentSnapshot(source.text().documentId(), 1, List.of("editor draft")), source.metadata(), source.binding(), source.reopenMode()));
        var queue = new QueueExecutor();
        var busy = new AtomicBoolean();
        try (var observer = new ExternalFileObserver(queue, false,
                (resource, mode) -> resource.storage().load(resource, mode, false), busy::get, () -> 0)) {
            observer.schedule(subject);
            var work = subject.io().beginSave();
            var completion = worker(work::perform);
            queue.runNext();
            assertInstanceOf(DocumentIoCoordinator.Saved.class, subject.io().accept(work, completion));
            assertTrue(observer.poll().isEmpty(), "An accepted own save supersedes the old baseline/generation");
            observer.schedule(subject); queue.runNext();
            busy.set(true);
            assertTrue(observer.poll().isEmpty());
            assertTrue(observer.busy());
            busy.set(false);
            assertEquals(ExternalFileObserver.Kind.UNCHANGED, only(observer).kind());
            assertEquals("editor draft", Files.readString(file));
        }
    }

    @Test
    void explicitRevalidationReturnsFreshUnchangedTextAndRejectsAnOlderNoticeVersion() throws Exception {
        Path file = Files.writeString(directory.resolve("revalidate.txt"), "original");
        var subject = subject(file, DocumentOpenMode.UTF_8);
        var expected = subject.binding();
        var unchanged = worker(() -> ExternalFileObserver.read(expected, file, DocumentOpenMode.UTF_8));
        assertEquals(ExternalFileObserver.Kind.UNCHANGED, unchanged.kind());
        assertEquals(List.of("original"), unchanged.loaded().lines());
        Files.writeString(file, "external one");
        var queue = new QueueExecutor();
        try (var observer = observer(queue, new AtomicLong(), (resource, mode) -> resource.storage().load(resource, mode, false))) {
            observer.schedule(subject); queue.runNext();
            var notice = only(observer);
            Files.writeString(file, "external two");
            var fresh = worker(() -> ExternalFileObserver.revalidate(notice));
            assertFalse(ExternalFileObserver.sameVersion(notice.loaded(), fresh.loaded()));
            assertEquals(List.of("external two"), fresh.loaded().lines());
            assertEquals(List.of("original"), subject.source().text().lines());
        }
    }

    @Test
    void unicodeBomChangesAreExplicitWhileBomlessLegacyFilesRetainTheirAcceptedDecoder() throws Exception {
        Path file = Files.writeString(directory.resolve("format.txt"), "original");
        var subject = subject(file, DocumentOpenMode.UTF_8);
        ResourceBinding expected = subject.binding();
        Files.write(file, withBom("первая\r\n", StandardCharsets.UTF_16LE, (byte) 0xFF, (byte) 0xFE));
        var utf16 = worker(() -> ExternalFileObserver.read(expected, file, DocumentOpenMode.UTF_8));
        assertEquals(ExternalFileObserver.Kind.CHANGED, utf16.kind());
        assertEquals(StandardCharsets.UTF_16LE, utf16.loaded().metadata().charset());
        assertEquals(BomKind.UTF_16_LE, utf16.loaded().metadata().bomKind());
        assertEquals(List.of("первая", ""), utf16.loaded().lines());
        var utf16Binding = binding(utf16.loaded());
        Files.writeString(file, "plain UTF-8\n");
        var utf8 = worker(() -> ExternalFileObserver.read(utf16Binding, file, DocumentOpenMode.UTF_16LE));
        assertEquals(StandardCharsets.UTF_8, utf8.loaded().metadata().charset());
        assertEquals(BomKind.NONE, utf8.loaded().metadata().bomKind());
        Files.write(file, withBom("UTF-8 BOM\n", StandardCharsets.UTF_8, (byte) 0xEF, (byte) 0xBB, (byte) 0xBF));
        var utf8Bom = worker(() -> ExternalFileObserver.read(expected, file, DocumentOpenMode.UTF_16LE));
        assertEquals(BomKind.UTF_8, utf8Bom.loaded().metadata().bomKind());
        assertEquals(List.of("UTF-8 BOM", ""), utf8Bom.loaded().lines());
        Files.write(file, withBom("big endian\r", StandardCharsets.UTF_16BE, (byte) 0xFE, (byte) 0xFF));
        var utf16Be = worker(() -> ExternalFileObserver.read(expected, file, DocumentOpenMode.UTF_8));
        assertEquals(BomKind.UTF_16_BE, utf16Be.loaded().metadata().bomKind());
        assertEquals(List.of("\r"), utf16Be.loaded().metadata().lineSeparators());
        Charset cp866 = Charset.forName("IBM866");
        Files.write(file, "старый текст".getBytes(cp866));
        var legacy = subject(file, DocumentOpenMode.CP866);
        var legacyBinding = legacy.binding();
        Files.write(file, "новый текст".getBytes(cp866));
        var next = worker(() -> ExternalFileObserver.read(legacyBinding, file, DocumentOpenMode.CP866));
        assertEquals(cp866, next.loaded().metadata().charset());
        assertEquals(List.of("новый текст"), next.loaded().lines());
    }

    @Test
    void explicitAcceptedDecodersSurviveAmbiguousUtf16AndUnchangedLegacyBomLikePrefixes() throws Exception {
        Path utf16File = directory.resolve("explicit-utf16.txt");
        Files.write(utf16File, withBom("\u0000A\u0000", StandardCharsets.UTF_16LE, (byte) 0xFF, (byte) 0xFE));
        var utf16 = subject(utf16File, DocumentOpenMode.UTF_16LE);
        var utf16Binding = utf16.binding();
        var unchanged = worker(() -> ExternalFileObserver.read(utf16Binding, utf16File, DocumentOpenMode.UTF_16LE));
        assertEquals(ExternalFileObserver.Kind.UNCHANGED, unchanged.kind());
        assertEquals(List.of("\u0000A\u0000"), unchanged.loaded().lines());
        Files.write(utf16File, withBom("\u0000B\u0000", StandardCharsets.UTF_16LE, (byte) 0xFF, (byte) 0xFE));
        var changed = worker(() -> ExternalFileObserver.read(utf16Binding, utf16File, DocumentOpenMode.UTF_16LE));
        assertEquals(ExternalFileObserver.Kind.CHANGED, changed.kind());
        assertEquals(List.of("\u0000B\u0000"), changed.loaded().lines());

        Path legacyFile = directory.resolve("explicit-legacy.txt");
        Files.write(legacyFile, withBom("legacy", StandardCharsets.US_ASCII, (byte) 0xEF, (byte) 0xBB, (byte) 0xBF));
        var legacy = subject(legacyFile, DocumentOpenMode.CP866);
        var legacyBinding = legacy.binding();
        var legacyText = legacy.source().text().lines();
        var loaded = worker(() -> ExternalFileObserver.read(legacyBinding, legacyFile, DocumentOpenMode.CP866));
        assertEquals(ExternalFileObserver.Kind.UNCHANGED, loaded.kind());
        assertEquals(Charset.forName("IBM866"), loaded.loaded().metadata().charset());
        assertEquals(legacyText, loaded.loaded().lines());
    }

    @ParameterizedTest
    @EnumSource(value = DocumentOpenMode.class, names = {"WINDOWS_1251", "CP866", "KOI8_R"})
    void changedLegacyBomLikePrefixesRemainTextDuringReadAndClickedVersionRevalidation(DocumentOpenMode mode) throws Exception {
        Path file = directory.resolve("legacy-bom-prefix.txt");
        byte[] original = withBom("legacy", StandardCharsets.US_ASCII, (byte) 0xEF, (byte) 0xBB, (byte) 0xBF);
        Files.write(file, original);
        var subject = subject(file, mode);
        var binding = subject.binding();
        var charset = subject.metadata().charset();
        String originalText = subject.source().text().line(0);
        Files.write(file, withBom("legacy tail\n", StandardCharsets.US_ASCII, (byte) 0xEF, (byte) 0xBB, (byte) 0xBF));

        var changed = worker(() -> ExternalFileObserver.read(binding, file, mode));
        assertEquals(ExternalFileObserver.Kind.CHANGED, changed.kind());
        assertEquals(charset, changed.loaded().metadata().charset());
        assertEquals(BomKind.NONE, changed.loaded().metadata().bomKind());
        assertEquals(List.of(originalText + " tail", ""), changed.loaded().lines());

        var notice = new ExternalFileObserver.Observation(subject, binding, subject.generation(), file, mode,
            ExternalFileObserver.Kind.CHANGED, changed.loaded());
        Files.write(file, withBom("legacy tail again\n", StandardCharsets.US_ASCII, (byte) 0xEF, (byte) 0xBB, (byte) 0xBF));
        var fresh = worker(() -> ExternalFileObserver.revalidate(notice));
        assertEquals(ExternalFileObserver.Kind.CHANGED, fresh.kind());
        assertEquals(charset, fresh.loaded().metadata().charset());
        assertEquals(BomKind.NONE, fresh.loaded().metadata().bomKind());
        assertEquals(List.of(originalText + " tail again", ""), fresh.loaded().lines());
        assertFalse(ExternalFileObserver.sameVersion(changed.loaded(), fresh.loaded()));
        assertEquals(List.of(originalText), subject.source().text().lines());
    }

    private static byte[] withBom(String text, Charset charset, byte... bom) {
        byte[] encoded = text.getBytes(charset);
        byte[] result = java.util.Arrays.copyOf(bom, bom.length + encoded.length);
        System.arraycopy(encoded, 0, result, bom.length, encoded.length);
        return result;
    }

    private static ExternalFileObserver observer(QueueExecutor queue, AtomicLong clock, ExternalFileObserver.Loader loader) {
        return new ExternalFileObserver(queue, false, loader, () -> false, clock::get);
    }

    private static void tick(ExternalFileObserver observer, FrozenDocumentSession subject, QueueExecutor queue, AtomicLong clock)
        throws Exception {
        observer.observe(subject, clock.addAndGet(ExternalFileObserver.CHECK_NANOS));
        queue.runNext();
    }

    private static FrozenDocumentSession subject(Path path, DocumentOpenMode mode) throws Exception {
        DocumentLoad loaded = worker(() -> JvmDocumentStorage.resource(path).storage().load(JvmDocumentStorage.resource(path), mode, false));
        return FrozenDocumentSession.create(new FrozenDocumentSource(
            new DocumentSnapshot(new DocumentId(path.toString()), 0, loaded.lines()), loaded.metadata(), binding(loaded), mode));
    }

    private static ResourceBinding binding(DocumentLoad loaded) {
        return new ResourceBinding(loaded.resource(), loaded.disposition(), loaded.baseline());
    }

    private static ExternalFileObserver.Observation only(ExternalFileObserver observer) {
        var observations = observer.poll();
        assertEquals(1, observations.size());
        return observations.getFirst();
    }

    private static <T> T worker(Callable<T> action) throws Exception {
        var task = new FutureTask<>(action);
        var thread = new Thread(task, "external-observer-test");
        thread.setDaemon(true); thread.start(); thread.join(5000);
        assertFalse(thread.isAlive(), "The bounded test worker must finish");
        return task.get();
    }

    private static final class QueueExecutor implements java.util.concurrent.Executor {
        final ArrayDeque<Runnable> tasks = new ArrayDeque<>();
        @Override public void execute(Runnable task) { tasks.addLast(task); }
        void runNext() throws Exception {
            assertFalse(tasks.isEmpty());
            Runnable task = tasks.removeFirst();
            worker(() -> { task.run(); return null; });
        }
    }
}
