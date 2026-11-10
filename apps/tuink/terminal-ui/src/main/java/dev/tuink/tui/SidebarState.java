package dev.tuink.tui;

import dev.tuiframe.cell.Canvas;
import dev.tuiframe.cell.CellText;
import dev.tuiframe.explorer.Explorer;
import dev.tuiframe.explorer.file.FileTreeModel;
import dev.tuiframe.explorer.ui.FileTreeView;
import dev.tuiframe.geometry.Rect;
import dev.tuiframe.widget.*;
import dev.tuink.tui.flow.PalettePreferences;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;

/** Window-owned palette dock. Files, search and their controllers keep independent lifetimes. */
final class SidebarState implements AutoCloseable {
    enum Content { FOLDERS, SEARCH }
    private static final Object TREE_SCOPE = new Object(), DIVIDER_SCOPE = new Object(), HEIGHT_SCOPE = new Object();
    final ProjectContext project = new ProjectContext();
    final ProjectSearchPanel search = new ProjectSearchPanel();
    private final EnumMap<Content, ToolPalette.State> headers = new EnumMap<>(Content.class);
    Content content = Content.FOLDERS, maximized;
    boolean headerFocused, filesExpanded = true, searchExpanded, searchFirst, filesHints = true;
    Explorer tree;
    boolean visible, focused, mutating;
    int preferredWidth = 30, filesHeight = 6, searchHeight = 18;
    int insertionRow = -1;
    Splitter.State splitterState = Splitter.State.initial(), heightState = Splitter.State.initial();
    Placement placement, capturePlacement;

    SidebarState() { for (var id : Content.values()) headers.put(id, ToolPalette.State.initial()); }
    Path root() { return project.root(); }
    boolean canUndo() { return tree != null && !mutating && tree.operations().canUndo(); }
    boolean canRedo() { return tree != null && !mutating && tree.operations().canRedo(); }
    String undoLabel() { return tree == null ? "Undo" : tree.operations().undoLabel(); }
    String redoLabel() { return tree == null ? "Redo" : tree.operations().redoLabel(); }
    FileTreeModel.Entry selectedEntry() { return tree == null ? null : tree.selectedEntry(); }
    boolean selectedRoot() { var selected = selectedEntry(); return selected != null && selected.path().equals(root()); }
    boolean selectedDirectory() { var selected = selectedEntry(); return selected != null && selected.kind() == FileTreeModel.Kind.DIRECTORY; }
    List<Content> order() { return searchFirst ? List.of(Content.SEARCH, Content.FOLDERS) : List.of(Content.FOLDERS, Content.SEARCH); }
    boolean expanded(Content id) { return id == Content.FOLDERS ? filesExpanded : searchExpanded; }
    boolean hintsVisible(Content id) { return id == Content.FOLDERS ? filesHints : search.hintsVisible; }
    private int height(Content id) { return id == Content.FOLDERS ? filesHeight : searchHeight; }
    private void height(Content id, int value) {
        int bounded = Math.max(1, Math.min(10_000, value));
        if (id == Content.FOLDERS) filesHeight = bounded; else searchHeight = bounded;
    }
    private void expanded(Content id, boolean value) { if (id == Content.FOLDERS) filesExpanded = value; else searchExpanded = value; }

    PalettePreferences preferences() {
        return new PalettePreferences(preferredWidth, filesHeight, searchHeight, filesExpanded, searchExpanded, searchFirst,
            maximized == null ? PalettePreferences.Maximized.NONE : maximized == Content.FOLDERS
                ? PalettePreferences.Maximized.FILES : PalettePreferences.Maximized.SEARCH, visible, filesHints, search.hintsVisible);
    }
    void defaults(PalettePreferences value) {
        preferredWidth = value.width(); filesHeight = value.filesHeight(); searchHeight = value.searchHeight();
        filesExpanded = value.filesExpanded(); searchExpanded = value.searchExpanded(); searchFirst = value.searchFirst();
        maximized = switch (value.maximized()) { case NONE -> null; case FILES -> Content.FOLDERS; case SEARCH -> Content.SEARCH; };
        visible = value.visible();
        filesHints = value.filesHints(); search.hintsVisible = value.searchHints();
        if (maximized != null) content = maximized;
    }
    void activate(Content id) {
        closeMenus(); content = id; expanded(id, true);
        if (maximized != null && maximized != id) maximized = null;
        visible = true; focused = true; headerFocused = false;
        if (id == Content.SEARCH) search.requestedFocus = ProjectSearchPanel.Target.QUERY;
    }
    void closeMenus() {
        search.dismissPopups();
        for (var id : Content.values()) if (headers.get(id).menuOpen()) headers.put(id, ToolPalette.State.initial());
    }
    boolean tick(long now) {
        if (!visible || !focused || placement == null) return false;
        boolean changed = false;
        for (var id : Content.values()) {
            var action = headers.get(id).tick(now);
            if (action.isPresent()) { adopt(id, action.orElseThrow()); changed = true; }
        }
        return changed;
    }
    long nanosUntilWakeup(long now) {
        if (!visible || !focused || placement == null) return -1L;
        long next = -1L;
        for (var state : headers.values()) {
            long delay = state.nanosUntilWakeup(now);
            if (delay >= 0L && (next < 0L || delay < next)) next = delay;
        }
        return next;
    }
    void open(Path root) {
        if (tree != null) tree.close();
        project.open(root); tree = Explorer.sidebar(root);
        content = maximized != null ? maximized : Content.FOLDERS;
        headerFocused = maximized == null && !expanded(content); visible = true; focused = true;
        splitterState = Splitter.State.initial(); heightState = Splitter.State.initial();
        closeMenus(); placement = null; capturePlacement = null;
    }
    SplitPane prepareLayout(Rect body) {
        if (!visible) { focused = false; closeMenus(); return null; }
        var split = SplitPane.sideBySide(body, preferredWidth, 16, 24);
        if (!split.divided()) { focused = false; closeMenus(); }
        return split;
    }
    Placement place(SplitPane split, Rect clip, boolean interactive) {
        if (split == null || !split.divided()) { placement = null; return null; }
        if (!focused || !interactive) closeMenus();
        Rect left = split.firstBounds();
        var items = order().stream().map(id -> new PaletteDock.Item<>(id, expanded(id), height(id),
            id == Content.FOLDERS ? 3 : ProjectSearchView.minimumHeight(search, left.width()))).toList();
        var dock = PaletteDock.layout(left, items, content, maximized);
        var files = dock.slot(Content.FOLDERS);
        var find = dock.slot(Content.SEARCH);
        var filesBody = ToolPalette.bodyLayout(files == null ? new Rect(left.x(), left.y(), 0, 0) : files.body(), filesHints, 1);
        // Overlays take input ownership without disabling the visible palette contents.
        var placedTree = files == null || files.body().isEmpty() || tree == null ? null
            : tree.place(filesBody.contentBounds(), clip, focused && content == Content.FOLDERS && !headerFocused && interactive, true);
        var searchView = find == null || find.body().isEmpty() ? null
            : search.place(find.body(), focused && content == Content.SEARCH && !headerFocused && interactive, true, root());
        if (focused && !headerFocused && (content == Content.FOLDERS ? placedTree == null && tree != null : searchView == null)) headerFocused = true;
        ArrayList<Header> placedHeaders = new ArrayList<>();
        var menuViewport = new Rect(clip.x(), left.y(), clip.width(), left.height()).intersection(clip);
        for (var slot : dock.slots()) {
            var id = slot.id();
            HashSet<ToolPalette.Action> disabled = new HashSet<>();
            if (order().getFirst() == id) disabled.add(ToolPalette.Action.MOVE_UP);
            else disabled.add(ToolPalette.Action.MOVE_DOWN);
            if (dock.dividers().isEmpty()) { disabled.add(ToolPalette.Action.TALLER); disabled.add(ToolPalette.Action.SHORTER); }
            String title = id == Content.FOLDERS ? "Files" : slot.body().isEmpty() ? ProjectSearchPresentation.collapsedTitle(search) : "Search";
            if (expanded(id) && slot.body().isEmpty()) title += " · compact";
            var view = new ToolPalette(title, expanded(id) || maximized == id, maximized == id, hintsVisible(id), headers.get(id), disabled).layout(slot.bounds(), menuViewport);
            var scope = FocusScope.single(id, FocusResetPolicy.CLEARED,
                new RoutedTarget<>("header", view, FocusSpace.root(left), true, interactive && !view.bounds().isEmpty()));
            placedHeaders.add(new Header(id, view, scope));
        }
        ArrayList<HeightDivider> dividers = new ArrayList<>();
        for (var divider : dock.dividers()) dividers.add(new HeightDivider(divider.before(), divider.after(),
            new Splitter(divider.split(), heightState).layout(), divider.split()));
        placement = new Placement(left, split.secondBounds(), placedTree, new Splitter(split, splitterState).layout(),
            root(), focused && interactive, List.copyOf(placedHeaders), searchView, dock, List.copyOf(dividers),
            headerFocused, content, insertionRow, filesBody, filesHint(left.width()));
        capturePlacement = placement;
        return placement;
    }
    private String filesHint(int width) {
        String enter = selectedDirectory() ? "Toggle" : "Open";
        var hints = List.of("↑↓ Navigate · ←→ Folders · Enter " + enter + " · Esc Editor",
            "↑↓ Move · Enter " + enter + " · Esc Editor", "↑↓ Move · Enter " + enter);
        return hints.stream().filter(text -> CellText.width(text) <= width).findFirst().orElse("Enter " + enter);
    }
    void resize(Splitter.StateChanged changed) { preferredWidth = Math.min(10_000, changed.firstExtent()); splitterState = changed.state(); }
    void resizeHeight(HeightDivider divider, Splitter.StateChanged changed) {
        height(divider.before(), changed.firstExtent() - 1);
        height(divider.after(), divider.split().bounds().height() - changed.firstExtent() - 2);
        heightState = changed.state();
    }
    void adopt(Content id, ToolPalette.Change change) {
        headers.put(id, change.state());
        if (change.action() == ToolPalette.Action.DRAG || !change.state().dragging()) insertionRow = -1;
        switch (change.action()) {
            case TOGGLE -> {
                if (maximized == id) { expanded(id, false); maximized = null; }
                else expanded(id, !expanded(id));
            }
            case MAXIMIZE -> maximized = maximized == id ? null : id;
            case TOGGLE_HINTS -> {
                if (id == Content.FOLDERS) filesHints = !filesHints;
                else search.hintsVisible = !search.hintsVisible;
            }
            case MOVE_UP -> { if (order().getFirst() != id) searchFirst = !searchFirst; }
            case MOVE_DOWN -> { if (order().getLast() != id) searchFirst = !searchFirst; }
            case WIDER -> preferredWidth = Math.min(10_000, preferredWidth + 2);
            case NARROWER -> preferredWidth = Math.max(16, preferredWidth - 2);
            case TALLER, SHORTER -> {
                if (placement != null && !placement.heights().isEmpty()) {
                    var divider = placement.heights().getFirst();
                    int delta = (change.action() == ToolPalette.Action.TALLER ? 2 : -2) * (divider.before() == id ? 1 : -1);
                    int extent = divider.split().clampFirstExtent((long) divider.split().firstExtent() + delta);
                    resizeHeight(divider, new Splitter.StateChanged(extent, Splitter.State.initial()));
                }
            }
            case DRAG, DROP -> {
                if (placement == null || !placement.bounds().contains(change.column(), change.row())) break;
                for (var slot : placement.dock().slots()) if (slot.id() != id) {
                    boolean before = change.row() < slot.bounds().y() + Math.max(1, slot.bounds().height() / 2);
                    int marker = before ? slot.bounds().y() : Math.max(slot.bounds().y(), slot.bounds().bottomExclusive() - 1);
                    if (change.action() == ToolPalette.Action.DRAG) insertionRow = marker;
                    else if ((order().getFirst() == id) != before) searchFirst = !searchFirst;
                }
            }
            default -> { }
        }
    }
    @Override public void close() {
        if (tree != null) tree.close(); tree = null; project.close();
        // Layout preferences survive disposal so normal-exit settings capture can persist them.
        focused = false; placement = null; capturePlacement = null;
        splitterState = Splitter.State.initial(); heightState = Splitter.State.initial(); insertionRow = -1;
        for (var id : Content.values()) headers.put(id, ToolPalette.State.initial());
    }
    record Header(Content id, ToolPalette.Placement view, FocusScope<String, ToolPalette.Change> scope) {}
    record HeightDivider(Content before, Content after, Splitter.Placement view, SplitPane split) {
        FocusScope<String, Splitter.StateChanged> scope() {
            return FocusScope.single(HEIGHT_SCOPE, FocusResetPolicy.CLEARED,
                new RoutedTarget<>("height", view, FocusSpace.root(split.bounds()), false, true));
        }
    }
    record Placement(Rect bounds, Rect documentBounds, FileTreeView.Placement tree,
                     Splitter.Placement divider, Path root, boolean focused, List<Header> headers,
                     ProjectSearchView.Placement search, PaletteDock.Layout<Content> dock,
                     List<HeightDivider> heights, boolean headerFocused, Content content, int insertionRow,
                     ToolPalette.BodyLayout filesBody, String filesHint) {
        Header header(Content id) { return headers.stream().filter(header -> header.id() == id).findFirst().orElse(null); }
        Header menu() { return headers.stream().filter(header -> header.view().menuOpen()).findFirst().orElse(null); }
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
            if (tree != null) tree.paint(canvas, theme);
            else {
                var files = dock.slot(Content.FOLDERS);
                if (files != null && !files.body().isEmpty()) CellText.drawClipped(canvas, files.body().x(), files.body().y(), " Open folder...", body, files.body().width());
            }
            filesBody.paintHint(canvas, theme, filesHint);
            if (search != null) search.paint(canvas, theme);
            for (var height : heights) height.view().paint(canvas, theme, new WidgetState(false, true));
            for (var header : headers) header.view().paint(canvas, theme,
                new WidgetState(focused && headerFocused && content == header.id(), true));
            if (insertionRow >= bounds.y() && insertionRow < bounds.bottomExclusive())
                CellText.drawClipped(canvas, bounds.x(), insertionRow, "━".repeat(bounds.width()),
                    theme.resolveStyle(WidgetThemeKeys.PALETTE_INSERTION, theme.resolveStyle(WidgetThemeKeys.BUTTON_FOCUSED, body)), bounds.width());
            divider.paint(canvas, theme, new WidgetState(focused, true));
        }
        void paintMenus(Canvas canvas, Theme theme) {
            for (var header : headers) header.view().paintMenu(canvas, theme);
        }
    }
}
