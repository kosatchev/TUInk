package dev.tuink.editor.format;

import dev.tuink.editor.storage.StorageOperation;
import java.io.IOException;

/** Internal backend capture. Validation reads immutable text and never writes a resource. */
public interface PreparedFormatChange {
    Validation perform(StorageOperation operation) throws IOException;

    /** Opaque proof issued only by the matching prepared backend operation. */
    interface Validation {}
}
