package dev.tuink.editor.storage;

/**
 * Immutable provider-scoped handle identity. Equality is defined by its provider;
 * it does not prove that two names refer to the same physical object.
 */
public interface ResourceId {
    String provider();
}
