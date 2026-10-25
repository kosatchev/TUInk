package dev.tuink.editor.storage;

import java.io.IOException;

/**
 * Blocking provider operations run by the host's worker. Owner capture, approval
 * and completion do not call this port. A provider must reject foreign handles
 * and approval tickets, validate any version used to authorize an owned-target
 * write, and never silently weaken a requested condition. Export to a different
 * resource may carry an unrelated source version; that version does not authorize
 * replacing the destination.
 */
public interface DocumentStorage {
    DocumentLoad load(DocumentResource resource, DocumentOpenMode mode, boolean allowMissing) throws IOException;
    SaveTarget inspect(DocumentResource resource, ResourceBinding current) throws IOException;
    boolean isCurrentTarget(DocumentResource resource, ResourceBinding current) throws IOException;

    /**
     * Return only a confirmed commit. Failures after a possible publication use
     * StorageWriteException with UNKNOWN; the owner then retains draft/recovery.
     * Current authorization retains the request resource identity in its receipt;
     * Approved authorization returns the inspected ticket's requested identity.
     * A physical baseline may identify a different resource behind an alias.
     */
    SavedDocument write(DocumentSave request, SaveAuthorization authorization) throws IOException;
}
