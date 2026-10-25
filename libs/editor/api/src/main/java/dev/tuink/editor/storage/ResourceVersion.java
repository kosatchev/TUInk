package dev.tuink.editor.storage;

/** Immutable provider-owned baseline for bytes actually read or committed. */
public interface ResourceVersion {
    ResourceId resourceId();
}
