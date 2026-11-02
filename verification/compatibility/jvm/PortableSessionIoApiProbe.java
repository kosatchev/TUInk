package dev.tuink.compat;

import dev.tuink.editor.document.DocumentId;
import dev.tuink.editor.document.PresentationId;
import dev.tuink.editor.document.PresentationGroupId;
import dev.tuink.editor.storage.FrozenDocumentSource;
import dev.tuink.editor.command.DocumentCommand;
import dev.tuink.editor.command.DocumentCommandResult;
import dev.tuink.editor.format.DocumentEncoding;
import dev.tuink.editor.format.DocumentFormatSpec;
import dev.tuink.editor.format.LineEndingPolicy;
import dev.tuink.editor.session.document.EditorDocumentSession;
import dev.tuink.editor.session.document.EditorWorkspace;
import dev.tuink.editor.session.document.DocumentIoCoordinator;
import dev.tuink.editor.session.document.DocumentSaveSubject;
import dev.tuink.editor.session.document.FrozenDocumentSession;
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
        saveAs((DocumentSaveSubject) session, destination);
        session.status().binding().resource().id();
    }

    static void saveAs(DocumentSaveSubject subject, DocumentResource destination) throws IOException {
        var io = subject.io();
        var inspect = io.beginSaveAs(destination, SaveDisposition.CREATE_NEW, false);
        var approval = (DocumentIoCoordinator.Approval) io.accept(inspect, inspect.perform());
        var ready = (DocumentIoCoordinator.Ready) io.approve(approval.confirmation());
        var saved = io.accept(ready.work(), ready.work().perform());
        if (saved instanceof DocumentIoCoordinator.Failed failure) throw failure.failure();
        subject.binding().resource().id();
    }

    static FrozenDocumentSession frozenSaveAs(FrozenDocumentSource source, DocumentResource destination)
        throws IOException {
        var frozen = FrozenDocumentSession.create(source);
        DocumentSaveSubject subject = frozen;
        saveAs(subject, destination);
        if (frozen.source().text() != source.text() || frozen.generation() != 1
            || !subject.binding().resource().id().equals(destination.id())) {
            throw new AssertionError("Frozen Save As must publish only the confirmed receipt and retain its source");
        }
        return frozen;
    }

    static EditorWorkspace<FrozenDocumentSource> projectionLifetime(EditorDocumentSession session) {
        var captured = session.freezeSource();
        var workspace = new EditorWorkspace<FrozenDocumentSource>();
        var group = new PresentationGroupId("external-presentation-group");
        var sourceId = new PresentationId("external-source-presentation");
        var projectionId = new PresentationId("external-projection-presentation");
        var source = new EditorWorkspace.Presentation<>(sourceId, group,
            new EditorWorkspace.Editing(session, session.defaultViewId()), captured);
        var projection = new EditorWorkspace.Presentation<>(projectionId, group,
            EditorWorkspace.Projection.INSTANCE, captured);
        workspace.insert(0, source, true);
        workspace.insert(1, projection, false);
        workspace.selectPresentationIndex(1);
        if (workspace.active() != null || workspace.activeIndex() != -1
            || !workspace.presentationAttachments().equals(List.of(captured, captured))) {
            throw new AssertionError("A projection has independent selection in the one presentation order");
        }
        workspace.replacePresentations(List.of(projection.withAttachment(captured)), projectionId);
        if (!workspace.entries().isEmpty() || workspace.document(session.documentId()) != null
            || session.workspace() != null || workspace.activePresentationIndex() != 0
            || workspace.find(projectionId).attachment().text() != captured.text()) {
            throw new AssertionError("A frozen projection must survive retiring the last editing view");
        }
        return workspace;
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
