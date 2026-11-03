package dev.tuink.tui;

import dev.tuink.core.EditorState;
import dev.tuink.editor.search.SearchOptions;
import dev.tuink.core.TextModel;
import dev.tuink.render.EditorLayout;
import dev.tuink.render.RenderPlanBuilder;
import dev.tuiframe.geometry.Rect;
import dev.tuink.tui.ui.ConfirmBarView;
import dev.tuink.tui.ui.FileTreeView;
import dev.tuink.tui.ui.HelpPage;
import dev.tuink.tui.ui.InlinePromptBar;
import dev.tuink.tui.ui.NotificationBar;
import dev.tuink.tui.ui.OpenFileDialogController.OpenDialogLayout;
import dev.tuink.tui.ui.SaveAsBarView;
import dev.tuink.tui.ui.SearchBarView;
import dev.tuink.tui.ui.TabBarView;
import java.util.Objects;

/**
 * Structurally immutable, exact-frame bundle consumed synchronously by the renderer.
 *
 * <p>The document collaborators remain owner-thread objects; this record freezes
 * their identity together with all scalar decisions and immutable widget
 * placements for one render call. It must not be retained across application
 * turns.</p>
 */
record FrameComposition(
    Document document,
    Surface surface,
    Search search,
    Layers layers,
    boolean wrapLines,
    boolean wrapWords,
    boolean fullRedraw
) {
    FrameComposition {
        Objects.requireNonNull(document, "document");
        Objects.requireNonNull(surface, "surface");
        Objects.requireNonNull(search, "search");
        Objects.requireNonNull(layers, "layers");
        if (document.layout().overlayRows() != surface.inlinePrompt().height()) {
            throw new IllegalArgumentException("Editor layout and prompt height must describe one frame");
        }
        int exclusiveOwners = (layers.saveAsMode() ? 1 : 0)
            + (search.placement() != null ? 1 : 0)
            + (layers.confirm() != null ? 1 : 0)
            + (layers.openFile().visible() ? 1 : 0)
            + (layers.helpPage() != null ? 1 : 0)
            + (layers.format() != null ? 1 : 0);
        if (exclusiveOwners > 1) {
            throw new IllegalArgumentException("Only one prompt or modal frame owner is allowed");
        }
    }

    boolean allowsScrollHints() {
        return !fullRedraw && layers.allowsScrollHints();
    }

    record Document(
        RenderPlanBuilder planBuilder,
        TextModel model,
        EditorState state,
        EditorLayout layout
    ) {
        Document {
            Objects.requireNonNull(planBuilder, "planBuilder");
            Objects.requireNonNull(model, "model");
            Objects.requireNonNull(state, "state");
            Objects.requireNonNull(layout, "layout");
        }
    }

    record Surface(
        TopMenuView.TopBar topBar,
        String bottomBar,
        InlinePromptBar inlinePrompt,
        NotificationBar notification
    ) {
        Surface {
            Objects.requireNonNull(topBar, "topBar");
            Objects.requireNonNull(bottomBar, "bottomBar");
            Objects.requireNonNull(inlinePrompt, "inlinePrompt");
            Objects.requireNonNull(notification, "notification");
        }
    }

    record Search(
        boolean active,
        String query,
        SearchOptions options,
        boolean autoSelectionHighlight,
        SearchBarView.Placement placement,
        boolean editorFocused
    ) {
        Search {
            Objects.requireNonNull(query, "query");
            Objects.requireNonNull(options, "options");
        }
    }

    record Layers(
        TabBarView.Placement tabBar,
        TopMenuView.Placement topMenu,
        boolean saveAsMode,
        SaveAsBarView.Placement saveAs,
        ConfirmBarView.Placement confirm,
        OpenFile openFile,
        TerminalEditorApp.HelpOverlayPlacement help,
        DocumentFormatDialogView.Placement format,
        boolean blockedInputHighlight,
        boolean contentFocused
    ) {
        HelpPage helpPage() { return help == null ? null : help.page(); }

        /** The picker may supply its own surface; every other blocking layer invalidates content reuse. */
        boolean allowsScrollHints() {
            return !blockedInputHighlight && help == null && format == null && confirm == null && !saveAsMode
                && (topMenu == null || !topMenu.state().isOpen())
                && !(contentFocused && openFile.visible());
        }

        Layers {
            Objects.requireNonNull(openFile, "openFile");
            if (!saveAsMode && saveAs != null) {
                throw new IllegalArgumentException("Save As placement requires Save As ownership");
            }
        }
    }

    record OpenFile(
        String rootLabel,
        OpenFileDialogView.Placement dialog,
        OpenFileInputRouter.Focus focus
    ) {
        private static final OpenFile HIDDEN = new OpenFile("", null, OpenFileInputRouter.Focus.LIST);

        OpenFile {
            Objects.requireNonNull(rootLabel, "rootLabel");
            Objects.requireNonNull(focus, "focus");
        }

        static OpenFile hidden() {
            return HIDDEN;
        }

        boolean visible() { return dialog != null; }
        OpenDialogLayout layout() { return dialog == null ? null : dialog.layout(); }
        FileTreeView.Placement placement() { return dialog == null ? null : dialog.tree(); }
        boolean showHiddenFiles() { return dialog != null && dialog.showHiddenFiles(); }

        int firstVisibleIndex() {
            return dialog == null ? 0 : dialog.tree().state().widgetState().firstVisibleIndex();
        }

        OpenTreeDiffKey diffKey() {
            FileTreeView.Placement placement = placement();
            if (placement == null) {
                return null;
            }
            OpenTreeSurfaceKey surface = new OpenTreeSurfaceKey(
                rootLabel,
                showHiddenFiles(),
                placement.state().snapshotRevision(),
                layout(),
                placement.bounds(),
                placement.contentBounds(),
                placement.leadingBounds(),
                placement.trailingBounds().orElse(null),
                placement.verticalScrollbarBounds().orElse(null),
                placement.horizontalScrollbarBounds().orElse(null),
                placement.itemCount(),
                placement.horizontalExtent(),
                placement.state().widgetState().horizontalOffset(),
                placement.sizeColumnWidth(),
                placement.dateColumnWidth()
            );
            return new OpenTreeDiffKey(surface, firstVisibleIndex());
        }
    }

    record OpenTreeDiffKey(OpenTreeSurfaceKey surface, int firstVisibleIndex) {
        OpenTreeDiffKey {
            Objects.requireNonNull(surface, "surface");
            if (firstVisibleIndex < 0) {
                throw new IllegalArgumentException("First visible tree index must not be negative");
            }
        }

        boolean isVerticalScrollFrom(OpenTreeDiffKey previous) {
            return previous != null
                && surface.equals(previous.surface)
                && firstVisibleIndex != previous.firstVisibleIndex;
        }
    }

    record OpenTreeSurfaceKey(
        String rootLabel,
        boolean showHiddenFiles,
        long snapshotRevision,
        OpenDialogLayout layout,
        Rect bounds,
        Rect contentBounds,
        Rect leadingBounds,
        Rect trailingBounds,
        Rect verticalScrollbarBounds,
        Rect horizontalScrollbarBounds,
        int itemCount,
        int horizontalExtent,
        int horizontalOffset,
        int sizeColumnWidth,
        int dateColumnWidth
    ) {
        OpenTreeSurfaceKey {
            Objects.requireNonNull(rootLabel, "rootLabel");
            Objects.requireNonNull(layout, "layout");
            Objects.requireNonNull(bounds, "bounds");
            Objects.requireNonNull(contentBounds, "contentBounds");
            Objects.requireNonNull(leadingBounds, "leadingBounds");
        }
    }
}
