package dev.tuink.editor.storage;

import java.util.Objects;

/** Distinguishes an owned-version save from approval of an inspected target. */
public sealed interface SaveAuthorization {
    /** Retains the request resource identity and loaded-version checks, including deleted owned names. */
    record Current() implements SaveAuthorization {}

    /** Writes only the approved observation, retains its owned-target checks, and returns its requested identity. */
    record Approved(SaveTarget target) implements SaveAuthorization {
        public Approved { Objects.requireNonNull(target, "target"); }
    }
}
