package dev.tuink.tui;

import dev.tuink.editor.storage.DocumentEncodingException;

import dev.tuink.core.TabSession;
import dev.tuink.editor.document.ViewId;
import dev.tuink.editor.storage.AmbiguousDocumentEncodingException;
import dev.tuink.editor.storage.DocumentOpenMode;
import dev.tuink.editor.storage.DocumentLoad;
import dev.tuink.editor.session.document.PreparedDocumentOpen;
import dev.tuink.storage.jvm.FileBaseline;
import dev.tuink.storage.jvm.JvmDocumentStorage;
import dev.tuink.tui.file.FileFailureReason;
import dev.tuink.tui.ui.NotificationPresentation;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/** Applies picker intents without owning window focus, renderers, or the tab collection. */
final class FilePickerOpenWorkflow {
    private FilePickerOpenWorkflow() {}

    enum NoticeKind { UNCHANGED, SUCCESS, WARNING, ERROR }

    record Result(boolean openFileMode, TuiDocumentSession openedDocument, int activateTabIndex,
                  NoticeKind noticeKind, String message, Notice notice, Path pendingExplicitOpenPath,
                  OpenRequest pendingOpen, ViewId activateViewId) {
        Result(boolean openFileMode, TuiDocumentSession openedDocument, int activateTabIndex,
               NoticeKind noticeKind, String message, Notice notice, Path pendingExplicitOpenPath,
               OpenRequest pendingOpen) {
            this(openFileMode, openedDocument, activateTabIndex, noticeKind, message, notice,
                pendingExplicitOpenPath, pendingOpen, null);
        }
        Result(boolean openFileMode, TuiDocumentSession openedDocument, int activateTabIndex,
               NoticeKind noticeKind, String message) {
            this(openFileMode, openedDocument, activateTabIndex, noticeKind, message,
                message == null ? null : new Notice(message, null), null, null);
        }

        Result(boolean openFileMode, TuiDocumentSession openedDocument, int activateTabIndex,
               NoticeKind noticeKind, String message, Notice notice) {
            this(openFileMode, openedDocument, activateTabIndex, noticeKind, message, notice, null, null);
        }
    }

    /** The worker owns decoding; publishing this result belongs to the UI thread. */
    record OpenRequest(Path path, DocumentOpenMode mode, PreparedDocumentOpen operation) {
        OpenRequest(Path path, DocumentOpenMode mode) {
            this(path, mode, new PreparedDocumentOpen(JvmDocumentStorage.resource(path), mode, false));
        }

        OpenRequest {
            Objects.requireNonNull(path, "path");
            Objects.requireNonNull(mode, "mode");
            Objects.requireNonNull(operation, "operation");
        }

        DocumentLoad read() throws IOException {
            return operation.read();
        }

        void cancel() { operation.cancel(); }
    }

    /** Plain failure data; the dialog owns its terminal presentation. */
    record Notice(String summary, Path path) {}

    static Path initialRoot(Path currentFile) {
        if (currentFile != null) {
            Path parent = currentFile.toAbsolutePath().normalize().getParent();
            if (parent != null) {
                return parent;
            }
        }
        return Path.of(".").toAbsolutePath().normalize();
    }

    static Result apply(OpenFileInputRouter.Intent intent, OpenFileTreeController tree,
                        List<TabSession> tabs, int activeTabIndex,
                        Supplier<TuiDocumentSession> newDocument) {
        return apply(intent, tree, tabs, activeTabIndex, newDocument, DocumentOpenMode.DETECT);
    }

    static Result apply(OpenFileInputRouter.Intent intent, OpenFileTreeController tree,
                        List<TabSession> tabs, int activeTabIndex,
                        Supplier<TuiDocumentSession> newDocument, DocumentOpenMode mode) {
        if (intent == null) {
            return unchanged(true);
        }
        if (intent instanceof OpenFileInputRouter.ToggleDirectory toggle) {
            tree.requestExpansion(toggle.id(), toggle.expand());
            return unchanged(true);
        }
        if (intent instanceof OpenFileInputRouter.ChangeRoot root) {
            tree.setRoot(root.root());
            return unchanged(true);
        }
        if (intent instanceof OpenFileInputRouter.MoveToParentRoot) {
            tree.moveToParentRoot();
            return unchanged(true);
        }
        if (intent instanceof OpenFileInputRouter.ToggleHidden) {
            tree.toggleHiddenFiles();
            return unchanged(true);
        }
        if (intent instanceof OpenFileInputRouter.CloseDialog) {
            return unchanged(false);
        }
        if (intent instanceof OpenFileInputRouter.SelectionRejected rejected) {
            return new Result(true, null, -1, NoticeKind.WARNING,
                rejected.reason() == OpenFileInputRouter.Rejection.ERROR
                    ? "The selected tree entry cannot be opened" : "Select a file");
        }
        if (intent instanceof OpenFileInputRouter.OpenFile open) {
            return open(open.file(), mode, tabs, activeTabIndex);
        }
        throw new IllegalStateException("Unsupported open-file intent: " + intent);
    }

    private static Result unchanged(boolean open) {
        return new Result(open, null, -1, NoticeKind.UNCHANGED, null);
    }

    private static Result failure(String reason, Path target) {
        return new Result(true, null, -1, NoticeKind.ERROR,
            "Open failed: " + reason + ": " + NotificationPresentation.path(target), new Notice(reason, target));
    }

    static Result openAsUtf16(Path path, List<TabSession> tabs, int activeTabIndex,
                              Supplier<TuiDocumentSession> newDocument) {
        return open(path, DocumentOpenMode.UTF_16LE, tabs, activeTabIndex);
    }

    private static Result open(Path target, DocumentOpenMode mode, List<TabSession> tabs, int activeTabIndex) {
        int existing = WorkspaceDocuments.findOpenFile(tabs, activeTabIndex, target);
        if (existing >= 0) {
            return switched(target, existing, tabs.get(existing));
        }
        return new Result(false, null, -1, NoticeKind.UNCHANGED, null, null, null,
            new OpenRequest(target, mode));
    }

    static Result complete(OpenRequest request, DocumentLoad loaded, IOException failure,
                           List<TabSession> tabs, int activeTabIndex,
                           Supplier<TuiDocumentSession> newDocument) {
        Objects.requireNonNull(request, "request");
        if (request.operation().cancelled()) return unchanged(false);
        if ((loaded == null) == (failure == null)) {
            throw new IllegalArgumentException("An open completion requires either loaded text or a failure");
        }
        if (loaded != null) {
            loaded = request.operation().accept(loaded);
            if (loaded == null) return unchanged(false);
        } else {
            request.cancel();
        }
        Path target = request.path();
        // Tabs may have changed while the read was running. Compare the file
        // actually read, including when its selected symlink was retargeted.
        Path identity = loaded == null ? target : ((FileBaseline) loaded.baseline()).target();
        int existing = WorkspaceDocuments.findOpenFile(tabs, activeTabIndex, identity);
        if (existing >= 0) return switched(target, existing, tabs.get(existing));
        if (loaded != null) {
            TuiDocumentSession document = newDocument.get().openPrepared(loaded);
            return new Result(false, document, -1, NoticeKind.SUCCESS,
                "Opened " + NotificationPresentation.path(target));
        }
        if (failure instanceof AmbiguousDocumentEncodingException) {
            return new Result(false, null, -1, NoticeKind.UNCHANGED, null, null, target, null);
        }
        FileFailureReason.Description described = FileFailureReason.describe(failure);
        String reason = described.detail() == null ? "I/O error" : described.detail();
        if (described.canAttachPath()) return failure(reason, target);
        return new Result(true, null, -1, NoticeKind.ERROR, "Open failed: " + reason,
            new Notice(reason, null));
    }

    private static Result switched(Path target, int existing, TabSession view) {
        return new Result(false, null, existing, NoticeKind.SUCCESS,
            "Switched to " + NotificationPresentation.path(target), null, null, null,
            new ViewId(view.state().viewId()));
    }
}
