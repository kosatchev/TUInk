package dev.tuink.editor.storage;

import java.util.Set;

/**
 * Immutable provider-owned observation of an exact target address and version.
 * It is stronger than a loaded byte baseline and must be retained through approval
 * and writing. Implementations validate their own tickets before publication.
 */
public interface SaveTarget {
    DocumentResource requestedResource();
    DocumentResource physicalResource();
    boolean exists();
    boolean currentTarget();
    Set<StorageCapability> capabilities();
}
