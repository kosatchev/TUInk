package dev.tuink.editor.storage;

/**
 * Immutable handle bound explicitly to its storage provider. Creating a handle
 * and reading its identity or name hints must not access the resource.
 */
public interface DocumentResource {
    ResourceId id();
    String displayName();
    /** A filename hint for language detection, independent of a presentation label. */
    String nameHint();
    DocumentStorage storage();
}
