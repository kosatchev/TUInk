package dev.tuink.tui;

import dev.tuink.core.DocumentEditLimitException;
import dev.tuink.editor.command.DocumentCommandResult;
import dev.tuink.editor.session.document.PreparedDocumentFormat;
import dev.tuink.editor.session.document.PreparedDocumentReopen;
import dev.tuink.editor.storage.DocumentEncodingException;
import dev.tuink.editor.storage.DocumentLoad;
import java.util.Locale;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.function.Predicate;

/** One cancellable conversion or re-read; publication stays on the session owner thread. */
final class DocumentFormatWorkflow {
    private final DocumentIoController io;
    private TuiDocumentSession owner;
    private DocumentFormatDialogView.State dialog;
    private PreparedDocumentFormat format;
    private PreparedDocumentReopen reopen;

    DocumentFormatWorkflow(DocumentIoController io) { this.io = io; }

    boolean affects(TuiDocumentSession session) {
        return owner != null && owner.document() == session.document();
    }

    void apply(TuiDocumentSession session, DocumentFormatDialogView.State state,
               BooleanSupplier live, Predicate<DocumentLoad> reopenAllowed, Consumer<Outcome> complete) {
        if (io.busy()) {
            complete.accept(new Outcome(false, "A file operation is already in progress"));
            return;
        }
        try {
            if (state.purpose == DocumentFormatDialogView.Purpose.FORMAT) {
                var prepared = session.document().prepareFormatChange(session.viewId(), state.specification());
                begin(session, state);
                format = prepared;
                io.submit(prepared::perform, result -> {
                    clear();
                    if (!live.getAsBoolean()) { prepared.cancel(); return; }
                    if (result.failure() != null) complete.accept(new Outcome(false, error(result.failure())));
                    else {
                        publish(complete, () -> {
                            DocumentCommandResult accepted = session.document().completeFormatChange(prepared, result.value());
                            return accepted instanceof DocumentCommandResult.Applied applied
                            ? new Outcome(true, applied.performed() ? "Document format changed; save to write the file" : "Document format is unchanged")
                            : new Outcome(false, "Document changed; review the format and try again");
                        });
                    }
                });
            } else {
                var prepared = session.document().prepareReopen(session.viewId(), state.openMode(), state.confirmDiscard);
                begin(session, state);
                reopen = prepared;
                io.submit(prepared::read, result -> {
                    clear();
                    if (!live.getAsBoolean()) { prepared.cancel(); return; }
                    if (result.failure() != null) complete.accept(new Outcome(false, error(result.failure())));
                    else if (!reopenAllowed.test(result.value())) {
                        prepared.cancel();
                        complete.accept(new Outcome(false, "File is owned by another tab; the draft is kept"));
                    }
                    else publish(complete, () -> session.document().completeReopen(prepared, result.value())
                        ? new Outcome(true, "Document reopened with the selected encoding")
                        : new Outcome(false, "Document changed; review the encoding and try again"));
                });
            }
        } catch (DocumentEditLimitException | IllegalStateException | IllegalArgumentException failure) {
            clear();
            complete.accept(new Outcome(false, failure.getMessage()));
        }
    }

    void cancel() {
        if (owner == null) return;
        if (format != null) format.cancel();
        if (reopen != null) reopen.cancel();
        io.cancelRead();
        clear();
    }

    private void begin(TuiDocumentSession session, DocumentFormatDialogView.State state) {
        owner = session;
        dialog = state;
        state.busy = true;
        state.error = null;
    }

    private void clear() {
        if (dialog != null) dialog.busy = false;
        owner = null;
        dialog = null;
        format = null;
        reopen = null;
    }

    private static String error(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof DocumentEncodingException encoding) {
                return String.format(Locale.ROOT, "U+%04X cannot encode: %s", encoding.codePoint(), encoding.charsetName());
            }
        }
        return failure.getMessage() == null ? "File operation failed; the draft is kept" : failure.getMessage();
    }

    private static void publish(Consumer<Outcome> complete, Supplier<Outcome> admission) {
        Outcome result;
        try {
            result = admission.get();
        } catch (DocumentEditLimitException | IllegalStateException | IllegalArgumentException failure) {
            result = new Outcome(false, error(failure));
        }
        complete.accept(result);
    }

    record Outcome(boolean succeeded, String message) {}
}
