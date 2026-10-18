package dev.tuink.tui;

import dev.tuink.core.TabSession;
import dev.tuink.core.AmbiguousDocumentEncodingException;
import dev.tuink.core.DocumentOpenMode;
import dev.tuink.tui.ui.NotificationPresentation;
import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileSystemException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Supplier;

/** Applies picker intents without owning window focus, renderers, or the tab collection. */
final class FilePickerOpenWorkflow {
    private FilePickerOpenWorkflow() {}

    enum NoticeKind { UNCHANGED, SUCCESS, WARNING, ERROR }

    record Result(boolean openFileMode, TuiDocumentSession openedDocument, int activateTabIndex,
                  NoticeKind noticeKind, String message, Notice notice, Path pendingExplicitOpenPath) {
        Result(boolean openFileMode, TuiDocumentSession openedDocument, int activateTabIndex,
               NoticeKind noticeKind, String message) {
            this(openFileMode, openedDocument, activateTabIndex, noticeKind, message,
                message == null ? null : new Notice(message, null), null);
        }

        Result(boolean openFileMode, TuiDocumentSession openedDocument, int activateTabIndex,
               NoticeKind noticeKind, String message, Notice notice) {
            this(openFileMode, openedDocument, activateTabIndex, noticeKind, message, notice, null);
        }
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
            return open(open.file(), DocumentOpenMode.DETECT, tabs, activeTabIndex, newDocument);
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
        return open(path, DocumentOpenMode.UTF_16LE, tabs, activeTabIndex, newDocument);
    }

    private static Result open(Path target, DocumentOpenMode mode, List<TabSession> tabs, int activeTabIndex,
                               Supplier<TuiDocumentSession> newDocument) {
        int existing = WorkspaceDocuments.findOpenFile(tabs, activeTabIndex, target);
        if (existing >= 0) {
            return new Result(false, null, existing, NoticeKind.SUCCESS,
                "Switched to " + NotificationPresentation.path(target));
        }
        try {
            TuiDocumentSession document = newDocument.get();
            document.view().engine().openExisting(target, mode);
            return new Result(false, document, -1, NoticeKind.SUCCESS,
                "Opened " + NotificationPresentation.path(target));
        } catch (AmbiguousDocumentEncodingException error) {
            return new Result(false, null, -1, NoticeKind.UNCHANGED, null, null, target);
        } catch (NoSuchFileException error) {
            return failure("file no longer exists", target);
        } catch (AccessDeniedException error) {
            return failure("permission denied", target);
        } catch (IOException error) {
            if (error instanceof FileSystemException filesystem && filesystem.getReason() != null
                && !filesystem.getReason().isBlank()) {
                return failure(filesystem.getReason(), target);
            }
            String reason = error.getMessage() == null ? "I/O error" : error.getMessage();
            return new Result(true, null, -1, NoticeKind.ERROR, "Open failed: " + reason,
                new Notice(reason, null));
        }
    }
}
