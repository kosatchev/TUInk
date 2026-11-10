package dev.tuink.editor.storage;

import java.io.IOException;

/**
 * Owner capture/publication port shared by editable documents and immutable source holders.
 * Detached inspection and writes read only their captured input; every other method belongs
 * to the subject's owner thread. The port does not imply editing views or an undo history.
 */
public interface DocumentSaveOperations {
    PreparedSave prepareSave() throws IOException;
    SavePreparation prepareSaveAsInput(DocumentResource resource, SaveDisposition disposition, boolean utf8)
        throws IOException;
    PreparedSave approveSaveAs(SaveTargetInspection inspection) throws IOException;
    default PreparedSave approveSaveAs(SaveTargetInspection inspection, boolean replaceExternalChanges) throws IOException {
        if (replaceExternalChanges) throw new IOException("This save subject cannot approve replacing an external version");
        return approveSaveAs(inspection);
    }
    void completeSaved(PreparedSave prepared, SavedDocument saved);

    interface PreparedSave {
        SavedDocument write() throws IOException;
        default SavedDocument writeWithOperation(StorageOperation operation) throws IOException {
            operation.beginPublication();
            return write();
        }
    }

    interface SavePreparation {
        SaveTargetInspection inspectTarget() throws IOException;
        default SaveTargetInspection inspectTargetWithOperation(StorageOperation operation) throws IOException {
            operation.checkCancelled();
            return inspectTarget();
        }
        SaveDisposition disposition();
    }

    interface SaveTargetInspection {
        DocumentResource requestedResource();
        DocumentResource physicalResource();
        boolean exists();
        boolean currentTarget();
    }
}
