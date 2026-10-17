package dev.tuink.tui;

import java.io.IOException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.function.Supplier;

/** Applies picker intents without owning window focus, renderers, or the tab collection. */
final class FilePickerOpenWorkflow {
    private FilePickerOpenWorkflow() {}

    enum NoticeKind { UNCHANGED, SUCCESS, WARNING, ERROR }

    record Result(boolean openFileMode, TuiDocumentSession openedDocument, NoticeKind noticeKind, String message) {}

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
            return new Result(true, null, NoticeKind.WARNING,
                rejected.reason() == OpenFileInputRouter.Rejection.ERROR
                    ? "The selected tree entry cannot be opened" : "Select a file");
        }
        if (intent instanceof OpenFileInputRouter.OpenFile open) {
            Path target = open.file();
            try {
                TuiDocumentSession document = newDocument.get();
                document.view().engine().openExisting(target);
                return new Result(false, document, NoticeKind.SUCCESS, "Opened " + target);
            } catch (NoSuchFileException error) {
                return new Result(true, null, NoticeKind.ERROR, "Open failed: file no longer exists: " + target);
            } catch (IOException error) {
                return new Result(true, null, NoticeKind.ERROR, "Open failed: " + error.getMessage());
            }
        }
        throw new IllegalStateException("Unsupported open-file intent: " + intent);
    }

    private static Result unchanged(boolean open) {
        return new Result(open, null, NoticeKind.UNCHANGED, null);
    }
}
