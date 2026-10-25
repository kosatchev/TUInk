package dev.tuink.editor.storage;

/** Guaranteed operations of an inspected target, not filesystem support guesses. */
public enum StorageCapability {
    READ,
    EXCLUSIVE_CREATE,
    REPLACE,
    ATOMIC_REPLACE,
    CONDITIONAL_WRITE
}
