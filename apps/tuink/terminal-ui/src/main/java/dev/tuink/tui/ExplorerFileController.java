package dev.tuink.tui;

import dev.tuink.editor.session.document.DocumentSaveSubject;
import dev.tuink.editor.storage.ResourceBinding;
import dev.tuink.storage.jvm.JvmDocumentStorage;
import dev.tuiframe.explorer.file.ExplorerFileService;
import dev.tuiframe.explorer.file.ExplorerOperations;
import java.util.concurrent.Callable;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Window-owned file actions with separate read inspection and durable mutation lifetimes. */
final class ExplorerFileController implements AutoCloseable {
    record Binding(DocumentSaveSubject subject, ResourceBinding expected) {}
    record Relocated(Binding binding, ResourceBinding replacement) {}
    record Outcome(ExplorerFileService.Result result, List<Relocated> bindings,
        ExplorerOperations operations, String historyAction) {}

    private final DocumentIoController io;
    private final Supplier<ExplorerOperations> operationsSource;
    private final Supplier<List<Binding>> bindings;
    private final Consumer<Outcome> accepted;
    private final Runnable invalidate;
    private ExplorerFileDialogView.State dialog;
    private DocumentIoController.Operation inspection;
    private boolean mutating;
    private record Inspection(ExplorerFileService.Target target, String unavailable) {}

    ExplorerFileController(DocumentIoController io, Supplier<ExplorerOperations> operationsSource, Supplier<List<Binding>> bindings,
        Consumer<Outcome> accepted, Runnable invalidate) {
        this.io = io; this.operationsSource = operationsSource; this.bindings = bindings; this.accepted = accepted; this.invalidate = invalidate;
    }

    ExplorerFileDialogView.State dialog() { return dialog; }
    boolean mutating() { return mutating; }

    void open(ExplorerFileService.Action action, Path source, Path parent) {
        open(action, source, parent, parent);
    }

    void open(ExplorerFileService.Action action, Path source, Path parent, Path root) {
        if (io.busy()) throw new IllegalStateException("A file operation is already in progress");
        close();
        var next = new ExplorerFileDialogView.State(action, source, parent, root);
        dialog = next;
        if (!next.creating()) {
            var operations = operationsSource.get();
            if (operations == null) return;
            io.submit(() -> {
                var target = operations.inspect(source);
                var availability = action == ExplorerFileService.Action.TRASH ? operations.trashAvailability(source) : null;
                return new Inspection(target, availability != null && !availability.available() ? availability.reason() : null);
            }, completion -> {
                inspection = null;
                if (dialog != next) return;
                if (completion.succeeded()) {
                    next.inspected = completion.value().target();
                    next.operationAvailable = completion.value().unavailable() == null;
                    next.error = completion.value().unavailable();
                }
                else next.error = completion.failure().getMessage();
                invalidate.run();
            });
            inspection = io.pendingOperation();
        }
        invalidate.run();
    }

    void confirm() {
        var draft = dialog;
        if (draft == null || mutating || io.busy()) return;
        if (!draft.operationAvailable || !draft.creating() && draft.inspected == null || !draft.destinationAllowed()) return;
        if (draft.naming() && draft.field.text().isBlank()) { draft.error = "Enter a name"; invalidate.run(); return; }
        var operations = operationsSource.get();
        if (operations == null) return;
        Path destination = draft.destinationFolder();
        String name = draft.naming() ? draft.field.text() : "";
        var target = draft.inspected;
        start(operations, draft.action, draft.source, draft, null,
            () -> operations.perform(draft.action, target, destination, name));
    }

    void move(ExplorerFileService.Target source, Path destination) {
        var operations = operationsSource.get();
        if (operations == null || mutating || io.busy()) return;
        start(operations, ExplorerFileService.Action.MOVE, source.path(), null, null,
            () -> operations.perform(ExplorerFileService.Action.MOVE, source, destination, ""));
    }

    void history(boolean redo) {
        var operations = operationsSource.get();
        if (operations == null || mutating || io.busy()) return;
        var entry = redo ? operations.redoEntry() : operations.undoEntry();
        if (entry == null) return;
        Path source = entry.source();
        start(operations, entry.action(), source, null, redo ? operations.redoLabel() : operations.undoLabel(),
            redo ? operations::redo : operations::undo);
    }

    private void start(ExplorerOperations operations, ExplorerFileService.Action action, Path source, ExplorerFileDialogView.State draft,
        String historyAction, Callable<ExplorerFileService.Result> mutation) {
        List<Binding> captured = bindings.get();
        if (captured.stream().anyMatch(binding -> binding.subject().io().busy())) {
            if (draft != null) draft.error = "Wait for document operations to finish";
            invalidate.run(); return;
        }
        mutating = true;
        if (draft != null) { draft.busy = true; draft.error = null; }
        io.submitMutation(() -> perform(operations, source, mutation, captured, historyAction), completion -> {
            mutating = false;
            if (draft != null) draft.busy = false;
            if (completion.succeeded()) {
                accepted.accept(completion.value());
                if (completion.value().result().succeeded()) {
                    if (draft != null && dialog == draft) { dialog = null; draft.close(); }
                } else if (draft != null && dialog == draft) draft.error = completion.value().result().failure().getMessage();
            } else {
                if (draft != null && dialog == draft) draft.error = completion.failure().getMessage();
                var failure = completion.failure() instanceof IOException problem ? problem
                    : new IOException("File operation failed", completion.failure());
                accepted.accept(new Outcome(new ExplorerFileService.Result(action,
                    source, null, false, false, failure), List.of(), operations, historyAction));
            }
            invalidate.run();
        });
        invalidate.run();
    }

    private Outcome perform(ExplorerOperations operations, Path source, Callable<ExplorerFileService.Result> mutation,
        List<Binding> captured, String historyAction) throws Exception {
        Path physicalSource = null;
        if (source != null && !Files.isSymbolicLink(source)) {
            try { physicalSource = source.toRealPath(); } catch (IOException ignored) { /* Receipt reports source loss. */ }
        }
        var result = mutation.call();
        ArrayList<Relocated> updates = new ArrayList<>();
        Path destinationPhysical = null;
        if (result.action().relocates() && result.destinationPublished()) {
            try { destinationPhysical = result.destination().toRealPath(); }
            catch (IOException unavailable) { destinationPhysical = result.destination().toAbsolutePath().normalize(); }
        }
        if (result.action().relocates() || result.action().removes())
            for (Binding binding : captured) {
            Path spelling = JvmDocumentStorage.pathOf(binding.expected().resource());
            Path physical = JvmDocumentStorage.boundPhysicalPath(binding.expected());
            Path relative = null, previous = null;
            if (spelling != null && spelling.toAbsolutePath().normalize().startsWith(source)) {
                previous = spelling; relative = source.relativize(spelling.toAbsolutePath().normalize());
            } else if (physicalSource != null && physical != null && physical.startsWith(physicalSource)) {
                previous = physical; relative = physicalSource.relativize(physical);
            }
            boolean confirmedRemoved = (result.action().removes() || result.action().relocates() && result.destinationPublished()) && (previous != null
                && result.removedPaths().stream().anyMatch(previous.toAbsolutePath().normalize()::startsWith)
                || physical != null && result.removedPaths().stream().anyMatch(physical::startsWith));
            if (previous == null || !confirmedRemoved && !(result.action().relocates() && result.succeeded())
                && !Files.notExists(previous, LinkOption.NOFOLLOW_LINKS)
                && (physical == null || !Files.notExists(physical, LinkOption.NOFOLLOW_LINKS))) continue;
            if (result.action().removes()) updates.add(new Relocated(binding, ResourceBinding.unbound()));
            else if (result.destinationPublished()) {
                Path next = result.destination().resolve(relative);
                if (physical != null) {
                    Path expectedPhysical = physicalSource != null && physical.startsWith(physicalSource)
                        ? destinationPhysical.resolve(physicalSource.relativize(physical)) : physical;
                    Path nextPhysical;
                    try { nextPhysical = next.toRealPath(); } catch (IOException unavailable) { nextPhysical = null; }
                    // Relative links inside a moved directory can also retarget.
                    // Follow the opened physical document, preserving its fingerprint.
                    if (!expectedPhysical.equals(nextPhysical)) next = expectedPhysical;
                }
                updates.add(new Relocated(binding, JvmDocumentStorage.relocatedBinding(binding.expected(), next)));
            }
        }
        return new Outcome(result, List.copyOf(updates), operations, historyAction);
    }

    @Override public void close() {
        if (mutating) return;
        if (inspection != null && io.pendingOperation() == inspection) io.cancelRead();
        inspection = null;
        if (dialog != null) dialog.close();
        dialog = null;
    }
}
