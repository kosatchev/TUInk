package dev.tuink.tui.ui;

import dev.tuink.render.Style;
import dev.tuink.tui.render.CellBuffer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * UI element that renders open editor tabs on a single row.
 */
public final class TabBar {
    private final Style baseStyle;
    private final Style baseMonoStyle;
    private final Style activeStyle;
    private final Style activeMonoStyle;
    private final List<TabHit> lastHits = new ArrayList<>();
    private int firstVisibleIndex;
    private boolean lastCanScrollLeft;
    private boolean lastCanScrollRight;
    private int lastCols;

    public TabBar(Style baseStyle, Style baseMonoStyle, Style activeStyle, Style activeMonoStyle) {
        this.baseStyle = baseStyle;
        this.baseMonoStyle = baseMonoStyle;
        this.activeStyle = activeStyle;
        this.activeMonoStyle = activeMonoStyle;
    }

    public void draw(CellBuffer buffer, int cols, List<TabItem> tabs, int activeIndex, boolean colorEnabled) {
        if (buffer.rows() < 2 || cols <= 0) {
            return;
        }
        lastHits.clear();
        lastCols = cols;
        lastCanScrollLeft = false;
        lastCanScrollRight = false;
        for (int col = 0; col < cols; col++) {
            buffer.set(1, col, " ", colorEnabled ? baseStyle : baseMonoStyle);
        }

        if (tabs.isEmpty()) {
            firstVisibleIndex = 0;
            return;
        }
        firstVisibleIndex = Math.max(0, Math.min(firstVisibleIndex, tabs.size() - 1));

        int viewportStart = 0;
        int viewportEnd = cols;
        boolean overflow = tabs.size() > 1 && totalWidth(tabs) > cols;
        if (overflow) {
            viewportStart = 1;
            viewportEnd = Math.max(1, cols - 1);
            lastCanScrollLeft = firstVisibleIndex > 0;
            lastCanScrollRight = hasHiddenRight(tabs, Math.max(1, cols - 2));
            Style monoOrColorBase = colorEnabled ? baseStyle : baseMonoStyle;
            buffer.set(1, 0, lastCanScrollLeft ? "<" : " ", monoOrColorBase);
            buffer.set(1, cols - 1, lastCanScrollRight ? ">" : " ", monoOrColorBase);
        } else {
            firstVisibleIndex = 0;
        }

        int col = viewportStart;
        for (int i = firstVisibleIndex; i < tabs.size() && col < viewportEnd; i++) {
            TabItem tab = tabs.get(i);
            boolean active = i == activeIndex;
            Style style = active
                ? (colorEnabled ? activeStyle : activeMonoStyle)
                : (colorEnabled ? baseStyle : baseMonoStyle);
            String text = formatTab(tab);
            if (col + text.length() > viewportEnd) {
                break;
            }
            int tabStart = col;
            int closeCol = -1;
            for (int j = 0; j < text.length() && col < viewportEnd; j++) {
                char ch = text.charAt(j);
                buffer.set(1, col, String.valueOf(ch), style);
                if (ch == 'x') {
                    closeCol = col;
                }
                col++;
            }
            int tabEnd = col - 1;
            if (tabEnd >= tabStart) {
                lastHits.add(new TabHit(i, tabStart, tabEnd, closeCol));
            }
            if (col < viewportEnd) {
                buffer.set(1, col, " ", colorEnabled ? baseStyle : baseMonoStyle);
                col++;
            }
        }
    }

    public void scrollLeft() {
        firstVisibleIndex = Math.max(0, firstVisibleIndex - 1);
    }

    public void scrollRight(List<TabItem> tabs) {
        if (tabs.isEmpty()) {
            firstVisibleIndex = 0;
            return;
        }
        firstVisibleIndex = Math.min(tabs.size() - 1, firstVisibleIndex + 1);
    }

    public Hit hit(int row, int col) {
        if (row != 1) {
            return new Hit(false, -1, false, false, false);
        }
        if (lastCanScrollLeft && col == 0) {
            return new Hit(true, -1, false, true, false);
        }
        if (lastCanScrollRight && col == Math.max(0, lastCols - 1)) {
            return new Hit(true, -1, false, false, true);
        }
        for (TabHit hit : lastHits) {
            if (col < hit.startCol() || col > hit.endCol()) {
                continue;
            }
            boolean close = hit.closeCol() >= 0 && col == hit.closeCol();
            return new Hit(true, hit.tabIndex(), close, false, false);
        }
        return new Hit(false, -1, false, false, false);
    }

    private String formatTab(TabItem item) {
        return "[" + formatTabTitle(item.file(), item.dirty(), item.hasMatch()) + " x]";
    }

    static String formatTabTitle(Path file, boolean dirty, boolean hasMatch) {
        String name = file == null ? "--no name--" : fileNameStatic(file);
        String left = hasMatch ? "#" : "";
        String right = dirty ? "*" : "";
        return left + name + right;
    }

    private int totalWidth(List<TabItem> tabs) {
        int total = 0;
        for (TabItem tab : tabs) {
            total += formatTab(tab).length() + 1;
        }
        return Math.max(0, total - 1);
    }

    private boolean hasHiddenRight(List<TabItem> tabs, int viewportWidth) {
        int col = 0;
        int lastVisible = firstVisibleIndex - 1;
        for (int i = firstVisibleIndex; i < tabs.size(); i++) {
            int width = formatTab(tabs.get(i)).length() + (col == 0 ? 0 : 1);
            if (col + width > viewportWidth) {
                break;
            }
            col += width;
            lastVisible = i;
        }
        return lastVisible < tabs.size() - 1;
    }

    private String fileName(Path path) {
        Path fileName = path.getFileName();
        if (fileName == null) {
            return path.toString();
        }
        return fileName.toString();
    }

    private static String fileNameStatic(Path path) {
        Path fileName = path.getFileName();
        if (fileName == null) {
            return path.toString();
        }
        return fileName.toString();
    }

    public record TabItem(Path file, boolean dirty, boolean hasMatch) {
    }

    public record Hit(boolean consumed, int tabIndex, boolean closeButton, boolean scrollLeft, boolean scrollRight) {
    }

    private record TabHit(int tabIndex, int startCol, int endCol, int closeCol) {
    }
}
