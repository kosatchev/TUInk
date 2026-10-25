package dev.tuink.editor.storage;

/** The publication operation that actually completed successfully. */
public enum PublicationKind {
    EXCLUSIVE_CREATE,
    ATOMIC_REPLACEMENT,
    REPLACEMENT,
    STREAM_WRITE
}
