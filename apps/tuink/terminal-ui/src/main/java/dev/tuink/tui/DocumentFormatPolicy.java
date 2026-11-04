package dev.tuink.tui;

import java.util.Objects;

/** Permission to execute a format-dialog purpose, shared by its placed button and command handler. */
final class DocumentFormatPolicy {
    record Decision(boolean allowed, String refusal) {
        Decision {
            if (allowed && refusal != null || !allowed && (refusal == null || refusal.isBlank()))
                throw new IllegalArgumentException("A refused format action needs an explanation");
        }
    }

    private static final Decision ALLOW = new Decision(true, null);
    private DocumentFormatPolicy() {}

    static Decision apply(DocumentFormatDialogView.Purpose purpose, ActiveSurface surface) {
        Objects.requireNonNull(purpose); Objects.requireNonNull(surface);
        if (surface.modalScope() == ActiveSurface.ModalScope.WINDOW)
            return new Decision(false, "Finish the current application dialog first");
        if (surface.windowIoBusy() || surface.documentLocked())
            return new Decision(false, "A file operation is already in progress");
        return switch (purpose) {
            case OPEN -> ALLOW;
            case REOPEN -> surface.preview()
                ? new Decision(false, "Use the source tab to reopen its encoding") : ALLOW;
            case FORMAT -> surface.preview()
                ? new Decision(false, "Use the source tab to change its format")
                : surface.reading() ? new Decision(false, "Reading mode: format changes are disabled") : ALLOW;
        };
    }
}
