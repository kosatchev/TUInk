package dev.tuink.compat;

import dev.tuink.editor.document.DocumentId;
import dev.tuink.editor.command.DocumentCommand;
import dev.tuink.editor.command.DocumentCommandResult;
import dev.tuink.editor.session.document.EditorDocumentSession;
import dev.tuink.editor.storage.DocumentOpenMode;
import dev.tuink.editor.storage.DocumentResource;
import dev.tuink.editor.storage.SaveDisposition;
import java.io.IOException;
import java.util.List;

/** Compiles with editor-session's API variant, without core on the compile classpath. */
final class PortableSessionIoApiProbe {
    static EditorDocumentSession open(DocumentResource source) throws IOException {
        EditorDocumentSession session = EditorDocumentSession.create(new DocumentId("external"), List.of(""));
        session.openPrepared(source.storage().load(source, DocumentOpenMode.DETECT, false));
        return session;
    }

    static void saveAs(EditorDocumentSession session, DocumentResource destination) throws IOException {
        EditorDocumentSession.SavePreparation input = session.prepareSaveAsInput(
            destination, SaveDisposition.CREATE_NEW, false);
        EditorDocumentSession.SaveTargetInspection observation = input.inspectTarget();
        EditorDocumentSession.PreparedSave prepared = session.approveSaveAs(observation);
        session.completeSaved(prepared, prepared.write());
        session.status().binding().resource().id();
    }

    static DocumentCommandResult type(EditorDocumentSession session, String text) {
        var target = session.commandTarget(session.defaultViewId());
        return session.execute(target, new DocumentCommand.Input(text));
    }
}
