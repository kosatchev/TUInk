package dev.tuink.tui;

import dev.tuink.editor.storage.StorageOperation;

/** User-facing status for a save's latest worker progress. */
final class SaveProgressPresentation {
    private SaveProgressPresentation() {}

    static String message(StorageOperation.Progress progress, boolean escapeAvailable) {
        String action = switch (progress.phase()) {
            case INSPECTING -> "Checking save target";
            case VERIFYING -> "Checking file changes";
            case STAGING -> "Preparing save";
            case PUBLISHING -> "Finishing save";
        };
        String amount = "";
        if (progress.totalBytes() > 0) {
            long divisor = divisor(progress.totalBytes());
            long total = progress.totalBytes() / divisor + (progress.totalBytes() % divisor == 0 ? 0 : 1);
            long completed = progress.completedBytes() == progress.totalBytes()
                ? total : progress.completedBytes() / divisor;
            amount = " " + completed + "/" + total + unit(divisor);
        } else if (progress.completedBytes() > 0) {
            long divisor = divisor(progress.completedBytes());
            amount = " " + progress.completedBytes() / divisor + unit(divisor);
        }
        return action + amount + (progress.cancellable() && escapeAvailable ? " · Esc to cancel" : "…");
    }

    private static long divisor(long bytes) {
        return bytes >= 1_048_576L ? 1_048_576L : bytes >= 1024L ? 1024L : 1L;
    }

    private static String unit(long divisor) {
        return divisor == 1L ? " B" : divisor == 1024L ? " KiB" : " MiB";
    }
}
