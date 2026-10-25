package dev.tuink.editor.storage;

import java.util.Objects;

/** Captured owner state used by a detached target inspection. */
public record ResourceBinding(DocumentResource resource, SaveDisposition disposition, ResourceVersion version) {
    public ResourceBinding {
        Objects.requireNonNull(disposition, "disposition");
        if (resource == null && (disposition != SaveDisposition.CREATE_NEW || version != null)) {
            throw new IllegalArgumentException("An unbound document cannot own an existing resource version");
        }
        if (version != null && disposition != SaveDisposition.REPLACE_EXISTING) {
            throw new IllegalArgumentException("A loaded version requires an existing resource binding");
        }
    }

    public static ResourceBinding unbound() {
        return new ResourceBinding(null, SaveDisposition.CREATE_NEW, null);
    }
}
