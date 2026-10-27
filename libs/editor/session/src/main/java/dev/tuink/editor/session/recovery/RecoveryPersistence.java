package dev.tuink.editor.session.recovery;

import java.io.IOException;

/** Host persistence boundary. A failed write may have published a checkpoint. */
public interface RecoveryPersistence<C> {
    void save(String entryId, C checkpoint) throws IOException;
    void delete(String entryId) throws IOException;
    void release(String entryId) throws IOException;
}
