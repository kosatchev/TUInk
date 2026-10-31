package dev.tuink.core;

import dev.tuink.editor.command.DocumentCommand;
import dev.tuink.editor.command.DocumentCommandResult;
import dev.tuink.editor.command.DocumentCommandTarget;
import dev.tuink.editor.backend.DocumentBackend;
import dev.tuink.editor.document.DocumentId;
import dev.tuink.editor.document.DocumentSnapshot;
import dev.tuink.editor.recovery.RecoveryDocument;
import dev.tuink.editor.recovery.RecoveryCheckpoint;
import dev.tuink.editor.document.TextEdit;
import dev.tuink.editor.document.TextPosition;
import dev.tuink.editor.document.ViewId;
import dev.tuink.editor.document.ViewSnapshot;
import dev.tuink.editor.document.WorkspaceEdit;
import dev.tuink.editor.storage.DocumentLoad;
import dev.tuink.editor.storage.DocumentResource;
import dev.tuink.editor.storage.SavedDocument;
import dev.tuink.editor.storage.SaveDisposition;
import dev.tuink.editor.storage.StorageOperation;
import java.io.IOException;
import java.util.AbstractList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/**
 * Core implementation of the session's neutral document port. Frontend composition
 * retains its own rendering view; this backend exposes only values and commands.
 */
public final class CoreDocumentBackend implements DocumentBackend {
    private final DocumentId documentId;
    private final EditorDocument document;
    private TabSession defaultView;
    private final ViewId defaultViewId;
    private final Thread ownerThread;
    private final Map<ViewId, TabSession> views = new HashMap<>();

    private CoreDocumentBackend(DocumentId documentId, TabSession defaultView) {
        this.documentId = Objects.requireNonNull(documentId, "documentId");
        this.defaultView = Objects.requireNonNull(defaultView, "defaultView");
        this.document = defaultView.engine().document();
        document.requireOwnerThread();
        this.ownerThread = Thread.currentThread();
        this.defaultViewId = registerView(defaultView);
    }

    /** Creates a logical view whose initial text starts at revision zero. */
    public static CoreDocumentBackend create(DocumentId documentId, List<String> initialLines) {
        DocumentSnapshot checked = new DocumentSnapshot(documentId, 0, initialLines);
        SimpleLineBuffer buffer = new SimpleLineBuffer();
        buffer.setLine(0, checked.line(0));
        for (int line = 1; line < checked.lineCount(); line++) {
            buffer.insertLine(line, checked.line(line));
        }
        EditorDocument document = new EditorDocument(new TextModel(buffer));
        TabSession view = document.attach(LogicalEditorTextLayout::new, new EditorClipboard());
        return new CoreDocumentBackend(documentId, view);
    }

    /** Adopts an existing frontend view without replacing its document or history. */
    public static CoreDocumentBackend adopt(DocumentId documentId, TabSession existingView) {
        return new CoreDocumentBackend(documentId, existingView);
    }

    /** Registers a frontend-owned view of the same document on its original owner. */
    public ViewId registerView(TabSession view) {
        requireOwnerThread();
        Objects.requireNonNull(view, "view");
        if (view.engine().document() != document) {
            throw new IllegalArgumentException("A registered view must belong to this document");
        }
        ViewId id = new ViewId(view.state().viewId());
        if (view.state().detachedView()) throw new IllegalArgumentException("A retired view cannot be registered again");
        TabSession previous = views.putIfAbsent(id, view);
        if (previous != null && previous != view) {
            throw new IllegalArgumentException("View identity is already registered");
        }
        if (previous == null && views.size() == 1 && defaultView != view) {
            // A logical document engine can bridge an interval without views.
            document.detach(defaultView.state());
            defaultView = view;
        }
        return id;
    }

    @Override public DocumentId documentId() { return documentId; }
    @Override public boolean isOwnerThread() { return Thread.currentThread() == ownerThread; }
    @Override public ViewId defaultViewId() { return defaultViewId; }

    @Override
    public boolean ownsView(ViewId viewId) {
        requireOwnerThread();
        return views.containsKey(Objects.requireNonNull(viewId, "viewId"));
    }

    @Override
    public ViewId attachView() {
        requireOwnerThread();
        return registerView(document.attach(LogicalEditorTextLayout::new, new EditorClipboard()));
    }

    @Override
    public void detachView(ViewId viewId) {
        TabSession removed = requireView(viewId);
        TabSession replacement = defaultView;
        if (removed == defaultView) {
            replacement = views.values().stream().filter(view -> view != removed).findFirst().orElse(null);
            if (replacement == null) {
                replacement = document.attach(LogicalEditorTextLayout::new, new EditorClipboard());
            }
        }
        document.detach(removed.state());
        views.remove(viewId);
        defaultView = replacement;
    }

    @Override
    public long revision() {
        requireOwnerThread();
        return document.model().revision();
    }

    @Override
    public long stateRevision() {
        requireOwnerThread();
        return defaultView.engine().recoveryRevision();
    }

    @Override
    public ViewSnapshot view(ViewId viewId) {
        TabSession view = requireView(viewId);
        EditorState state = view.state();
        return new ViewSnapshot(documentId, viewId, document.model().revision(),
            view.engine().navigationRevision(), position(state.getCursor()), position(state.getSelectionAnchor()));
    }

    @Override
    public long viewRevision(ViewId viewId) {
        return requireView(viewId).engine().navigationRevision();
    }

    @Override
    public DocumentSnapshot snapshot() {
        requireOwnerThread();
        TextModel model = document.model();
        return DocumentSnapshot.fromImmutableLines(documentId, model.revision(),
            model.snapshotTextLines().stream().map(LineText::immutableText).toList());
    }

    @Override
    public Status status() {
        requireOwnerThread();
        EditorEngine engine = defaultView.engine();
        return new Status(engine.resourceBinding(), document.model().fileMetadata(),
            defaultView.state().isDirty(), engine.recoveryRevision());
    }

    @Override
    public DocumentCommandResult.Applied execute(DocumentCommandTarget target, DocumentCommand command) {
        requireOwnerThread();
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(command, "command");
        if (!documentId.equals(target.documentId())) {
            throw new IllegalArgumentException("Command belongs to another document");
        }
        TabSession view = requireView(target.viewId());
        if (document.model().revision() != target.revision()) {
            throw new IllegalStateException("Command target is stale");
        }
        if (view.engine().recoveryRevision() != target.stateRevision()) {
            throw new IllegalStateException("Command document state is stale");
        }
        if (view.engine().navigationRevision() != target.viewRevision()) {
            throw new IllegalStateException("Command target is stale");
        }
        return view.engine().executeCommand(target, command);
    }

    @Override
    public <T> T applyWorkspaceEdit(WorkspaceEdit edit, Function<Boolean, T> prepareResult) {
        requireOwnerThread();
        Objects.requireNonNull(edit, "edit");
        Objects.requireNonNull(prepareResult, "prepareResult");
        if (!documentId.equals(edit.documentId()) || document.model().revision() != edit.expectedRevision()) {
            throw new IllegalArgumentException("Workspace edit does not match this document revision");
        }
        List<TextReplacement> replacements = new AbstractList<>() {
            @Override public int size() { return edit.edits().size(); }
            @Override public TextReplacement get(int index) {
                TextEdit replacement = edit.edits().get(index);
                return new TextReplacement(position(replacement.range().start()), position(replacement.range().end()),
                    replacement.replacement());
            }
        };
        return defaultView.engine().applyTextReplacements(replacements, prepareResult);
    }

    @Override
    public <T> T undo(Function<Boolean, T> prepareResult) {
        requireOwnerThread();
        return defaultView.engine().undo(prepareResult);
    }

    @Override
    public <T> T redo(Function<Boolean, T> prepareResult) {
        requireOwnerThread();
        return defaultView.engine().redo(prepareResult);
    }

    @Override
    public void openPrepared(DocumentLoad loaded) {
        requireOwnerThread();
        defaultView.engine().openPrepared(loaded);
    }

    @Override
    public RecoveryCheckpoint prepareRecovery(ViewId viewId) {
        TabSession view = requireView(viewId);
        RecoverySnapshot.Prepared prepared = RecoverySnapshot.prepare(document.model(), view.state());
        return prepared::materialize;
    }

    @Override
    public void restoreRecovery(ViewId viewId, RecoveryDocument checkpoint) {
        TabSession view = requireView(viewId);
        Objects.requireNonNull(checkpoint, "checkpoint");
        List<String> lines = List.copyOf(checkpoint.lines());
        ViewportState viewport = view.state().viewport();
        if (viewport.topLine() >= lines.size()) {
            viewport = new ViewportState(Math.max(0, lines.size() - 1), 0,
                viewport.leftColumn(), viewport.leftVisualColumn());
        }
        RecoverySnapshot restored = new RecoverySnapshot(lines, position(checkpoint.caret()), viewport,
            position(checkpoint.anchor()), checkpoint.dirty(), checkpoint.currentResource(), checkpoint.saveDisposition(),
            checkpoint.fileMetadata(), checkpoint.fileBaseline(), checkpoint.createdAt());
        view.engine().restoreRecoverySnapshot(restored);
    }

    @Override
    public PreparedSave prepareSave() throws IOException {
        requireOwnerThread();
        EditorEngine engine = defaultView.engine();
        return new Save(engine, engine.prepareSave());
    }

    @Override
    public SavePreparation prepareSaveAsInput(DocumentResource resource, SaveDisposition disposition, boolean utf8)
        throws IOException {
        requireOwnerThread();
        EditorEngine engine = defaultView.engine();
        return new Preparation(engine, engine.prepareSaveAsInput(resource, disposition, utf8));
    }

    @Override
    public PreparedSave approveSaveAs(SaveTargetInspection inspection) throws IOException {
        requireOwnerThread();
        if (!(inspection instanceof Inspection captured) || captured.owner != this) {
            throw new IllegalArgumentException("Save inspection belongs to another document backend");
        }
        return new Save(captured.engine, captured.engine.approveSaveAs(captured.inspection));
    }

    @Override
    public void completeSaved(PreparedSave prepared, SavedDocument saved) {
        requireOwnerThread();
        if (!(prepared instanceof Save captured) || captured.owner != this) {
            throw new IllegalArgumentException("Prepared save belongs to another document backend");
        }
        captured.engine.completeSaved(captured.prepared, saved);
    }

    private TabSession requireView(ViewId viewId) {
        requireOwnerThread();
        TabSession view = views.get(Objects.requireNonNull(viewId, "viewId"));
        if (view == null) throw new IllegalArgumentException("View is not registered with this document backend");
        return view;
    }

    private void requireOwnerThread() {
        if (!isOwnerThread()) {
            throw new IllegalStateException("Document backend mutation requires its owner thread");
        }
        document.requireOwnerThread();
    }

    private static TextPosition position(Position value) {
        return value == null ? null : new TextPosition(value.line(), value.col());
    }

    private static Position position(TextPosition value) {
        return value == null ? null : new Position(value.line(), value.utf16Offset());
    }

    private final class Save implements PreparedSave {
        private final CoreDocumentBackend owner = CoreDocumentBackend.this;
        private final EditorEngine engine;
        private final EditorEngine.PreparedSave prepared;
        private Save(EditorEngine engine, EditorEngine.PreparedSave prepared) {
            this.engine = engine;
            this.prepared = prepared;
        }
        @Override public SavedDocument write() throws IOException { return prepared.write(); }
        @Override public SavedDocument writeWithOperation(StorageOperation operation) throws IOException {
            return prepared.writeWithOperation(operation);
        }
    }

    private final class Preparation implements SavePreparation {
        private final EditorEngine engine;
        private final EditorEngine.SavePreparation preparation;
        private Preparation(EditorEngine engine, EditorEngine.SavePreparation preparation) {
            this.engine = engine;
            this.preparation = preparation;
        }
        @Override public SaveTargetInspection inspectTarget() throws IOException {
            return new Inspection(engine, preparation.inspectTarget());
        }
        @Override public SaveTargetInspection inspectTargetWithOperation(StorageOperation operation) throws IOException {
            return new Inspection(engine, preparation.inspectTargetWithOperation(operation));
        }
        @Override public SaveDisposition disposition() { return preparation.disposition(); }
    }

    private final class Inspection implements SaveTargetInspection {
        private final CoreDocumentBackend owner = CoreDocumentBackend.this;
        private final EditorEngine engine;
        private final EditorEngine.SaveTargetInspection inspection;
        private Inspection(EditorEngine engine, EditorEngine.SaveTargetInspection inspection) {
            this.engine = engine;
            this.inspection = inspection;
        }
        @Override public DocumentResource requestedResource() { return inspection.requestedResource(); }
        @Override public DocumentResource physicalResource() { return inspection.physicalResource(); }
        @Override public boolean exists() { return inspection.exists(); }
        @Override public boolean currentTarget() { return inspection.currentTarget(); }
    }
}
