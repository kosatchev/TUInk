package dev.tuink.tui.ui;

import dev.tuink.tui.render.CellBuffer;
import dev.tuink.render.Style;
import dev.tuink.tui.render.VerticalScrollbar;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Reusable folder-tree UI element: tree model, navigation, rendering and mouse hit-testing.
 */
public final class TreeView {
    private Path root;
    private final Set<Path> expandedDirs = new HashSet<>();
    private List<Item> items = List.of();
    private int selectedIndex;
    private int scroll;
    private int lastClickIndex = -1;
    private long lastClickAtNanos;

    public TreeView(Path root) {
        setRoot(root);
    }

    public Path root() {
        return root;
    }

    public int selectedIndex() {
        return selectedIndex;
    }

    public int visibleIndex(int listRows) {
        ensureVisible(listRows);
        return Math.max(0, Math.min(selectedIndex - scroll, Math.max(0, listRows - 1)));
    }

    public Item selectedItem() {
        if (items.isEmpty() || selectedIndex < 0 || selectedIndex >= items.size()) {
            return null;
        }
        return items.get(selectedIndex);
    }

    public void setRoot(Path root) {
        this.root = root.toAbsolutePath().normalize();
        expandedDirs.clear();
        rebuild();
        selectedIndex = 0;
        scroll = 0;
        resetClickTracking();
    }

    public void moveUp(int listRows) {
        if (items.isEmpty()) {
            return;
        }
        selectedIndex = Math.max(0, selectedIndex - 1);
        ensureVisible(listRows);
    }

    public void moveDown(int listRows) {
        if (items.isEmpty()) {
            return;
        }
        selectedIndex = Math.min(items.size() - 1, selectedIndex + 1);
        ensureVisible(listRows);
    }

    public void pageUp(int listRows) {
        if (items.isEmpty()) {
            return;
        }
        int step = Math.max(1, listRows);
        selectedIndex = Math.max(0, selectedIndex - step);
        ensureVisible(listRows);
    }

    public void pageDown(int listRows) {
        if (items.isEmpty()) {
            return;
        }
        int step = Math.max(1, listRows);
        selectedIndex = Math.min(items.size() - 1, selectedIndex + step);
        ensureVisible(listRows);
    }

    public void moveHome(int listRows) {
        selectedIndex = 0;
        ensureVisible(listRows);
    }

    public void moveEnd(int listRows) {
        selectedIndex = Math.max(0, items.size() - 1);
        ensureVisible(listRows);
    }

    public void scrollUp(int listRows) {
        selectedIndex = Math.max(0, selectedIndex - 1);
        ensureVisible(listRows);
    }

    public void scrollDown(int listRows) {
        selectedIndex = Math.min(Math.max(0, items.size() - 1), selectedIndex + 1);
        ensureVisible(listRows);
    }

    public void moveToParentDirectory() {
        Path parent = root.getParent();
        if (parent != null) {
            setRoot(parent);
        }
    }

    public void moveToParentDepth(int listRows) {
        Item selected = selectedItem();
        if (selected == null) {
            return;
        }
        int targetDepth = Math.max(0, selected.depth() - 1);
        for (int i = selectedIndex - 1; i >= 0; i--) {
            Item candidate = items.get(i);
            if (candidate.directory() && candidate.depth() == targetDepth) {
                selectedIndex = i;
                ensureVisible(listRows);
                break;
            }
        }
    }

    public boolean toggleSelectedAndRefresh() {
        Item selected = selectedItem();
        if (selected == null) {
            return false;
        }
        if (selected.parentLink()) {
            setRoot(selected.path());
            return true;
        }
        if (!selected.directory()) {
            return false;
        }
        Path key = selected.path().toAbsolutePath().normalize();
        if (expandedDirs.contains(key)) {
            expandedDirs.remove(key);
        } else {
            expandedDirs.add(key);
        }
        Path selectedPath = selected.path().toAbsolutePath().normalize();
        rebuild();
        int restored = findIndexByPath(selectedPath);
        if (restored >= 0) {
            selectedIndex = restored;
        } else {
            selectedIndex = Math.min(selectedIndex, Math.max(0, items.size() - 1));
        }
        scroll = Math.min(scroll, Math.max(0, items.size() - 1));
        return true;
    }

    public ClickResult clickList(
        int mouseRow,
        int mouseCol,
        int left,
        int right,
        int listStartRow,
        int listRows,
        long nowNanos,
        long doubleClickWindowNanos
    ) {
        int listBottom = listStartRow + listRows - 1;
        if (mouseCol < left + 1 || mouseCol > right - 1 || mouseRow < listStartRow || mouseRow > listBottom) {
            return new ClickResult(false, false, false, null);
        }
        int clicked = scroll + (mouseRow - listStartRow);
        if (clicked < 0 || clicked >= items.size()) {
            return new ClickResult(true, false, false, null);
        }

        selectedIndex = clicked;
        ensureVisible(listRows);
        Item selected = items.get(selectedIndex);

        int arrowCol = left + 2 + Math.max(0, selected.depth()) * 2;
        boolean arrowClick = selected.directory() && mouseCol == arrowCol;
        boolean doubleClick = clicked == lastClickIndex && nowNanos - lastClickAtNanos <= doubleClickWindowNanos;
        lastClickIndex = clicked;
        lastClickAtNanos = nowNanos;

        return new ClickResult(true, arrowClick, doubleClick, selected);
    }

    public void resetClickTracking() {
        lastClickIndex = -1;
        lastClickAtNanos = 0L;
    }

    public void draw(
        CellBuffer buffer,
        int cols,
        int left,
        int right,
        int listStartRow,
        int listRows,
        boolean colorEnabled,
        Style normalStyle,
        Style selectedColorStyle,
        Style selectedMonoStyle,
        Style scrollTrackStyle,
        Style scrollThumbStyle
    ) {
        ensureVisible(listRows);
        int areaWidth = Math.max(0, right - left);
        boolean showScrollbar = areaWidth >= 26;
        int textWidth = Math.max(0, areaWidth - (showScrollbar ? 1 : 0));
        for (int i = 0; i < listRows; i++) {
            int itemIndex = scroll + i;
            int row = listStartRow + i;
            String label = itemIndex < items.size() ? items.get(itemIndex).label() : "";
            Style style = itemIndex == selectedIndex
                ? (colorEnabled ? selectedColorStyle : selectedMonoStyle)
                : normalStyle;
            drawLine(buffer, row, left + 1, padRight(" " + label, textWidth), style, cols);
        }
        if (showScrollbar && !items.isEmpty()) {
            VerticalScrollbar.draw(
                buffer,
                listStartRow,
                right - 1,
                listRows,
                items.size(),
                scroll,
                "│",
                scrollTrackStyle,
                "█",
                scrollThumbStyle
            );
        }
    }

    private void ensureVisible(int listRows) {
        int rows = Math.max(1, listRows);
        int maxScroll = Math.max(0, items.size() - rows);
        if (selectedIndex < scroll) {
            scroll = selectedIndex;
        }
        int lastVisible = scroll + rows - 1;
        if (selectedIndex > lastVisible) {
            scroll = selectedIndex - rows + 1;
        }
        scroll = Math.max(0, Math.min(scroll, maxScroll));
    }

    private void rebuild() {
        List<Item> out = new ArrayList<>();
        Path parent = root.getParent();
        if (parent != null) {
            out.add(new Item(parent, "..", true, false, true, 0));
        }
        appendTreeItems(root, 0, out);
        items = out;
    }

    private int findIndexByPath(Path path) {
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).path().toAbsolutePath().normalize().equals(path)) {
                return i;
            }
        }
        return -1;
    }

    private void appendTreeItems(Path directory, int depth, List<Item> out) {
        List<Path> children;
        try {
            children = listDirectorySorted(directory);
        } catch (IOException e) {
            out.add(new Item(directory, "<cannot read directory: " + e.getMessage() + ">", false, false, false, depth));
            return;
        }

        for (Path child : children) {
            boolean dir = Files.isDirectory(child);
            Path normalized = child.toAbsolutePath().normalize();
            boolean expanded = dir && expandedDirs.contains(normalized);
            String label = formatTreeLabel(depth, child, dir, expanded);
            out.add(new Item(child, label, dir, expanded, false, depth));
            if (dir && expanded) {
                appendTreeItems(child, depth + 1, out);
            }
        }
    }

    private List<Path> listDirectorySorted(Path directory) throws IOException {
        try (var stream = Files.list(directory)) {
            return stream
                .sorted(
                    Comparator
                        .comparing((Path p) -> !Files.isDirectory(p))
                        .thenComparing(p -> p.getFileName().toString().toLowerCase(Locale.ROOT))
                )
                .toList();
        }
    }

    private String formatTreeLabel(int depth, Path path, boolean directory, boolean expanded) {
        String indent = "  ".repeat(Math.max(0, depth));
        String name = path.getFileName() == null ? path.toString() : path.getFileName().toString();
        if (!directory) {
            return indent + "  " + name;
        }
        return indent + (expanded ? "▼ " : "▶ ") + name + "/";
    }

    private static void drawLine(CellBuffer buffer, int row, int startCol, String text, Style style, int cols) {
        for (int i = 0; i < text.length() && startCol + i < cols; i++) {
            buffer.set(row, startCol + i, String.valueOf(text.charAt(i)), style);
        }
    }

    private static String padRight(String text, int width) {
        if (text.length() >= width) {
            return text.substring(0, width);
        }
        return text + " ".repeat(width - text.length());
    }

    public record Item(Path path, String label, boolean directory, boolean expanded, boolean parentLink, int depth) {
    }

    public record ClickResult(boolean consumed, boolean arrowClick, boolean doubleClick, Item item) {
    }
}
