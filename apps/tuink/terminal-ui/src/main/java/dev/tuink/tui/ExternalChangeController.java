package dev.tuink.tui;

import dev.tuink.editor.session.document.DocumentIoCoordinator;
import dev.tuink.editor.session.document.DocumentSaveSubject;
import dev.tuink.editor.session.document.EditorDocumentSession;
import dev.tuink.editor.session.document.FrozenDocumentSession;
import dev.tuink.editor.storage.DocumentOpenMode;
import dev.tuink.editor.storage.ResourceBinding;
import dev.tuink.storage.jvm.JvmDocumentStorage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.function.Consumer;

/** Revalidates an explicit owner decision without reading files on the window thread. */
final class ExternalChangeController implements AutoCloseable {
    record Attempt(DocumentSaveSubject subject, ResourceBinding expected, long generation, Path path,
        DocumentOpenMode mode, ExternalDocumentNotices.Notice notice, boolean automatic) {}
    record Outcome(Attempt attempt, ExternalFileObserver.ReadResult result, IOException failure) {}

    private final DocumentIoController io;
    private final ExternalDocumentNotices notices;
    private final Consumer<Outcome> accepted;
    private final Runnable invalidate;
    private Attempt pending;
    private DocumentIoController.Operation operation;

    ExternalChangeController(DocumentIoController io, ExternalDocumentNotices notices,
        Consumer<Outcome> accepted, Runnable invalidate) {
        this.io = Objects.requireNonNull(io);
        this.notices = Objects.requireNonNull(notices);
        this.accepted = Objects.requireNonNull(accepted);
        this.invalidate = Objects.requireNonNull(invalidate);
    }

    boolean busy() { return pending != null; }

    boolean start(DocumentSaveSubject subject, ExternalDocumentNotices.Notice notice, boolean automatic) {
        if (subject == null || pending != null || io.busy() || subject.io().busy()
            || subject.io().state() == DocumentIoCoordinator.State.STOPPED) return false;
        ResourceBinding binding = subject.binding();
        Path path = JvmDocumentStorage.pathOf(binding.resource());
        if (path == null || notice != null && (notice.subject() != subject || !notice.expected().equals(binding))) return false;
        if (notice != null && !notices.setBusy(notice, true)) return false;
        Attempt attempt = new Attempt(subject, binding, generation(subject), path, subject.reopenMode(), notice, automatic);
        pending = attempt;
        DocumentOpenMode mode = attempt.mode();
        io.submit(() -> ExternalFileObserver.read(binding, path, mode), completion -> {
            if (pending != attempt) return;
            pending = null;
            operation = null;
            if (notice != null) notices.setBusy(notice, false);
            accepted.accept(new Outcome(attempt, completion.value(), completion.failure()));
            invalidate.run();
        });
        if (pending == attempt) operation = io.pendingOperation();
        invalidate.run();
        return true;
    }

    static long generation(DocumentSaveSubject subject) {
        if (subject instanceof EditorDocumentSession live) return live.status().recoveryRevision();
        if (subject instanceof FrozenDocumentSession frozen) return frozen.generation();
        throw new IllegalArgumentException("Unsupported document subject");
    }

    @Override public void close() {
        var attempt = pending;
        pending = null;
        if (operation != null && io.pendingOperation() == operation) io.cancelRead();
        operation = null;
        if (attempt != null && attempt.notice() != null) notices.setBusy(attempt.notice(), false);
        invalidate.run();
    }
}
