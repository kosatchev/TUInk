package dev.tuink.tui;

import dev.tuiframe.explorer.file.FileTreeModel;

import dev.tuiframe.explorer.Explorer;

import dev.tuiframe.cell.Canvas;
import dev.tuiframe.cell.CellText;
import dev.tuiframe.geometry.Rect;
import dev.tuiframe.widget.FocusResetPolicy;
import dev.tuiframe.widget.FocusScope;
import dev.tuiframe.widget.FocusSpace;
import dev.tuiframe.widget.RoutedTarget;
import dev.tuiframe.widget.SplitPane;
import dev.tuiframe.widget.Splitter;
import dev.tuiframe.widget.Theme;
import dev.tuiframe.widget.WidgetState;
import dev.tuiframe.widget.WidgetThemeKeys;
import dev.tuiframe.explorer.ui.FileTreeView;
import dev.tuiframe.explorer.ui.PathPresentation;
import java.nio.file.Path;

/** Window-owned folder navigation, independent of document and picker lifetimes. */
final class ExplorerState implements AutoCloseable {
    private static final Object TREE_SCOPE = new Object();
    private static final Object DIVIDER_SCOPE = new Object();
    Explorer tree;
    boolean visible;
    boolean focused;
    int preferredWidth = 30;
    Splitter.State splitterState = Splitter.State.initial();
    Placement placement;
    Placement capturePlacement;

    FileTreeModel.Entry selectedEntry() { return tree == null ? null : tree.selectedEntry(); }

    Path root() { return tree == null ? null : tree.root(); }

    void open(Path root) {
        if (tree != null) tree.close();
        tree = Explorer.sidebar(root);
        visible = true;
        focused = true;
        splitterState = Splitter.State.initial();
        placement = null;
        capturePlacement = null;
    }

    Rect documentBounds(Rect body) {
        return visible ? SplitPane.sideBySide(body, preferredWidth, 16, 24).secondBounds() : body;
    }

    Placement place(Rect body, Rect clip, boolean interactive) {
        if (!visible) { placement = null; return null; }
        var split = SplitPane.sideBySide(body, preferredWidth, 16, 24);
        if (!split.divided()) { focused = false; placement = null; return null; }
        Rect left = split.firstBounds();
        Rect list = new Rect(left.x(), left.y() + Math.min(1, left.height()), left.width(), Math.max(0, left.height() - 1));
        if (list.isEmpty()) focused = false;
        var placedTree = tree == null ? null : tree.place(list, clip, focused && interactive, true);
        placement = new Placement(left, split.secondBounds(), placedTree,
            new Splitter(split, splitterState).layout(), root(), focused && interactive);
        capturePlacement = placement;
        return placement;
    }

    void resize(Splitter.StateChanged changed) {
        preferredWidth = changed.firstExtent();
        splitterState = changed.state();
    }

    @Override public void close() {
        if (tree != null) tree.close();
        tree = null;
        visible = false;
        focused = false;
        placement = null;
        capturePlacement = null;
        splitterState = Splitter.State.initial();
    }

    record Placement(Rect bounds, Rect documentBounds, FileTreeView.Placement tree,
        Splitter.Placement divider, Path root, boolean focused) {
        FocusScope<String, FileTreeView.Action> treeScope() {
            return FocusScope.single(TREE_SCOPE, FocusResetPolicy.CLEARED,
                new RoutedTarget<>("tree", tree.captureTarget(), FocusSpace.root(bounds), false, tree.enabled()));
        }
        FocusScope<String, Splitter.StateChanged> dividerScope() {
            return FocusScope.single(DIVIDER_SCOPE, FocusResetPolicy.CLEARED,
                new RoutedTarget<>("divider", divider, FocusSpace.root(divider.bounds()), false, true));
        }
        void paint(Canvas canvas, Theme theme) {
            var body = theme.resolveStyle(WidgetThemeKeys.MODAL_BODY);
            canvas.fill(bounds, body);
            var header = theme.resolveStyle(WidgetThemeKeys.MODAL_BORDER, body);
            String caption = root == null ? " Explorer" : " " + PathPresentation.name(root);
            if (tree != null && tree.loading()) caption += " (Loading...)";
            CellText.drawClipped(canvas, bounds.x(), bounds.y(), caption, header, bounds.width());
            if (tree != null) tree.paint(canvas, theme);
            else if (bounds.height() > 1) CellText.drawClipped(canvas, bounds.x(), bounds.y() + 1,
                " Open folder...", body, bounds.width());
            divider.paint(canvas, theme, new WidgetState(focused, true));
        }
    }
}
