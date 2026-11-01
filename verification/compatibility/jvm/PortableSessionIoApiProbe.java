package dev.tuink.compat;

import dev.tuink.editor.document.DocumentId;
import dev.tuink.editor.command.DocumentCommand;
import dev.tuink.editor.command.DocumentCommandResult;
import dev.tuink.editor.format.DocumentEncoding;
import dev.tuink.editor.format.DocumentFormatSpec;
import dev.tuink.editor.format.LineEndingPolicy;
import dev.tuink.editor.session.document.EditorDocumentSession;
import dev.tuink.editor.session.document.EditorWorkspace;
import dev.tuink.editor.session.document.DocumentIoCoordinator;
import dev.tuink.editor.session.document.PreparedDocumentOpen;
import dev.tuink.editor.recovery.RecoveryDocument;
import dev.tuink.editor.storage.DocumentOpenMode;
import dev.tuink.editor.storage.DocumentResource;
import dev.tuink.editor.storage.SaveDisposition;
import java.io.IOException;
import java.util.List;

/** Compiles with editor-session's API variant, without core on the compile classpath. */
final class PortableSessionIoApiProbe {
    static EditorDocumentSession open(DocumentResource source) throws IOException {
        EditorDocumentSession session = EditorDocumentSession.create(new DocumentId("external"), List.of(""));
        var request = new PreparedDocumentOpen(source, DocumentOpenMode.DETECT, false);
        session.openPrepared(request.accept(request.read()));
        return session;
    }

    static void saveAs(EditorDocumentSession session, DocumentResource destination) throws IOException {
        var io = session.io();
        var inspect = io.beginSaveAs(destination, SaveDisposition.CREATE_NEW, false);
        var approval = (DocumentIoCoordinator.Approval) io.accept(inspect, inspect.perform());
        var ready = (DocumentIoCoordinator.Ready) io.approve(approval.confirmation());
        var saved = io.accept(ready.work(), ready.work().perform());
        if (saved instanceof DocumentIoCoordinator.Failed failure) throw failure.failure();
        session.status().binding().resource().id();
    }

    static RecoveryDocument checkpoint(EditorDocumentSession session) {
        return session.prepareRecovery(session.defaultViewId()).materialize();
    }

    static DocumentCommandResult convert(EditorDocumentSession session) throws IOException {
        var prepared = session.prepareFormatChange(session.defaultViewId(),
            new DocumentFormatSpec(DocumentEncoding.CP866, LineEndingPolicy.CRLF));
        // A frontend runs perform() in its worker and admits completion on the owner.
        var completion = prepared.perform();
        return session.completeFormatChange(prepared, completion);
    }

    static DocumentCommandResult type(EditorDocumentSession session, String text) {
        var target = session.commandTarget(session.defaultViewId());
        return session.execute(target, new DocumentCommand.Input(text));
    }

    static EditorWorkspace<String> workspace(EditorDocumentSession session) {
        var workspace = new EditorWorkspace<String>();
        var original = session.defaultViewId();
        var captured = session.commandTarget(original);
        workspace.add(session, original, "first layout");
        var attached = session.attachView();
        workspace.addAll(List.of(new EditorWorkspace.Entry<>(session, attached, "second layout")));
        workspace.select(attached);
        workspace.remove(original);
        session.execute(attached, new DocumentCommand.Edit(DocumentCommand.EditAction.UNDO));
        if (!(session.execute(captured, new DocumentCommand.Input("retired")) instanceof DocumentCommandResult.Rejected)) {
            throw new AssertionError("Retired view must reject deferred commands");
        }
        workspace.updateAttachment(attached, "replacement layout");
        workspace.attachments().get(workspace.activeIndex());
        workspace.document(session.documentId()).prepareRecovery(workspace.active().viewId());
        return workspace;
    }
}
