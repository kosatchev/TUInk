package dev.tuiframe.explorer.file;

import java.nio.file.Path;

/** Optional system-trash integration. Providers never fall back to permanent deletion. */
public interface TrashProvider {
    record Availability(boolean available, String reason) {
        public static Availability supported() { return new Availability(true, ""); }
        public static Availability unavailable(String reason) { return new Availability(false, reason); }
    }

    @FunctionalInterface
    interface Transfer {
        ExplorerFileService.Result move(ExplorerFileService.Target source, Path destination);
    }

    /** Read-only, potentially blocking filesystem inspection; call from an I/O worker. */
    Availability availability(Path source);

    ExplorerFileService.Result trash(ExplorerFileService.Target source, Transfer transfer);
}
