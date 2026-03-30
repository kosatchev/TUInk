package dev.tuink.tui.ui;

import dev.tuink.tui.render.CellBuffer;
import dev.tuink.tui.render.CellText;
import dev.tuink.render.ScrollInteraction;
import dev.tuink.render.Style;
import dev.tuink.tui.render.HorizontalScrollbar;
import dev.tuink.tui.render.VerticalScrollbar;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
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
    private static final int MIN_LABEL_WIDTH_WITH_META = 12;
    private static final int DEFAULT_SIZE_COLUMN_WIDTH = 11;
    private static final int MIN_SIZE_COLUMN_WIDTH = 7;
    private static final int DATE_COLUMN_WIDTH = 14;
    private static final String UNKNOWN_DATE = "--.--.-- --:--";
    private static final String PENDING_META = "...";
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("dd.MM.yy HH:mm", Locale.ROOT);

    private Path root;
    private final Set<Path> expandedDirs = new HashSet<>();
    private List<Item> items = List.of();
    private int maxLabelLength;
    private int selectedIndex;
    private int scroll;
    private int hScroll;
    private int lastClickIndex = -1;
    private long lastClickAtNanos;
    private boolean showSizeColumn;
    private boolean showDateColumn;
    private boolean showHiddenFiles;
    private final Object metadataLock = new Object();
    private long metadataGeneration;
    private Thread metadataWorker;
    private volatile boolean metadataChanged;

    public TreeView(Path root) {
        this(root, false, false);
    }

    public TreeView(Path root, boolean showSizeColumn, boolean showDateColumn) {
        this.showSizeColumn = showSizeColumn;
        this.showDateColumn = showDateColumn;
        setRoot(root);
    }

    public Path root() {
        return root;
    }

    public int selectedIndex() {
        return selectedIndex;
    }

    public int scrollOffset() {
        return scroll;
    }

    public boolean showHiddenFiles() {
        return showHiddenFiles;
    }

    public void setShowHiddenFiles(boolean enabled) {
        if (showHiddenFiles == enabled) {
            return;
        }
        Path selectedPath = selectedItem() == null ? null : selectedItem().path().toAbsolutePath().normalize();
        showHiddenFiles = enabled;
        rebuild();
        restoreSelection(selectedPath);
        scroll = Math.min(scroll, Math.max(0, items.size() - 1));
    }

    public void setShowSizeColumn(boolean enabled) {
        if (showSizeColumn == enabled) {
            return;
        }
        Path selectedPath = selectedItem() == null ? null : selectedItem().path().toAbsolutePath().normalize();
        showSizeColumn = enabled;
        rebuild();
        restoreSelection(selectedPath);
    }

    public void setShowDateColumn(boolean enabled) {
        if (showDateColumn == enabled) {
            return;
        }
        Path selectedPath = selectedItem() == null ? null : selectedItem().path().toAbsolutePath().normalize();
        showDateColumn = enabled;
        rebuild();
        restoreSelection(selectedPath);
    }

    public int visibleIndex(int listRows) {
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
        hScroll = 0;
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
        if (items.isEmpty()) {
            return;
        }
        int rows = Math.max(1, listRows);
        scroll = Math.max(0, scroll - 1);
        clampScroll(rows);
    }

    public void scrollDown(int listRows) {
        if (items.isEmpty()) {
            return;
        }
        int rows = Math.max(1, listRows);
        scroll = Math.min(maxScrollForRows(rows), scroll + 1);
        clampScroll(rows);
    }

    public void scrollLeft() {
        hScroll = Math.max(0, hScroll - 2);
    }

    public void scrollRight(int visibleTextWidth) {
        int max = maxHorizontalScroll(visibleTextWidth);
        if (max <= 0) {
            hScroll = 0;
            return;
        }
        hScroll = Math.min(max, hScroll + 2);
    }

    // Width of scrollable label text area (used by horizontal list navigation).
    public int visibleTextWidth(int left, int right, int listRows) {
        return computeLayout(left, right, listRows).labelWidth();
    }

    // Full row width inside list body: label + metadata columns (used by ANSI fast-path region).
    public int visibleRowWidth(int left, int right, int listRows) {
        return computeLayout(left, right, listRows).textWidth();
    }

    public ScrollPointerResult handleScrollbarPointer(
        int mouseRow,
        int mouseCol,
        int left,
        int right,
        int listStartRow,
        int listRows
    ) {
        DrawLayout layout = computeLayout(left, right, listRows);
        int textRows = layout.textRows();
        if (layout.showHScrollbar() && layout.labelWidth() > 0) {
            int row = listStartRow + textRows;
            int startCol = left + 1;
            int total = Math.max(layout.labelWidth(), maxLabelLength());
            ScrollInteraction.Hit hit = HorizontalScrollbar.hit(
                mouseRow,
                mouseCol,
                row,
                startCol,
                layout.labelWidth(),
                total,
                hScroll
            );
            if (hit == ScrollInteraction.Hit.THUMB) {
                hScroll = HorizontalScrollbar.scrollFromPointer(
                    mouseCol,
                    startCol,
                    layout.labelWidth(),
                    total
                );
                return new ScrollPointerResult(true, ScrollAxis.HORIZONTAL, true);
            }
            if (hit == ScrollInteraction.Hit.TRACK) {
                hScroll = HorizontalScrollbar.pageScrollFromTrackClick(
                    hScroll,
                    mouseCol,
                    startCol,
                    layout.labelWidth(),
                    total
                );
                return new ScrollPointerResult(true, ScrollAxis.HORIZONTAL, false);
            }
        }
        if (layout.showVScrollbar()) {
            int col = right - 1;
            int top = listStartRow;
            ScrollInteraction.Hit hit = VerticalScrollbar.hit(
                mouseRow,
                mouseCol,
                top,
                col,
                textRows,
                items.size(),
                scroll
            );
            if (hit == ScrollInteraction.Hit.THUMB) {
                scroll = VerticalScrollbar.scrollFromPointer(mouseRow, top, textRows, items.size());
                clampScroll(textRows);
                return new ScrollPointerResult(true, ScrollAxis.VERTICAL, true);
            }
            if (hit == ScrollInteraction.Hit.TRACK) {
                scroll = VerticalScrollbar.pageScrollFromTrackClick(scroll, mouseRow, top, textRows, items.size());
                clampScroll(textRows);
                return new ScrollPointerResult(true, ScrollAxis.VERTICAL, false);
            }
        }
        return new ScrollPointerResult(false, ScrollAxis.NONE, false);
    }

    public void dragScrollbarPointer(
        int mouseRow,
        int mouseCol,
        int left,
        int right,
        int listStartRow,
        int listRows,
        ScrollAxis axis
    ) {
        if (axis == ScrollAxis.NONE) {
            return;
        }
        DrawLayout layout = computeLayout(left, right, listRows);
        if (axis == ScrollAxis.HORIZONTAL && layout.showHScrollbar() && layout.labelWidth() > 0) {
            int startCol = left + 1;
            hScroll = HorizontalScrollbar.scrollFromPointer(
                mouseCol,
                startCol,
                layout.labelWidth(),
                Math.max(layout.labelWidth(), maxLabelLength())
            );
            return;
        }
        if (axis == ScrollAxis.VERTICAL && layout.showVScrollbar()) {
            int textRows = layout.textRows();
            int top = listStartRow;
            scroll = VerticalScrollbar.scrollFromPointer(mouseRow, top, textRows, items.size());
            clampScroll(textRows);
        }
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
        restoreSelection(selectedPath);
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
        DrawLayout layout = computeLayout(left, right, listRows);
        int listBottom = listStartRow + layout.textRows() - 1;
        if (mouseCol < left + 1 || mouseCol > right - 1 || mouseRow < listStartRow || mouseRow > listBottom) {
            return new ClickResult(false, false, false, null);
        }
        ScrollPointerResult scrollPointer = handleScrollbarPointer(mouseRow, mouseCol, left, right, listStartRow, listRows);
        if (scrollPointer.consumed()) {
            return new ClickResult(true, false, false, null);
        }
        int clicked = scroll + (mouseRow - listStartRow);
        if (clicked < 0 || clicked >= items.size()) {
            return new ClickResult(true, false, false, null);
        }

        selectedIndex = clicked;
        ensureVisible(layout.textRows());
        Item selected = items.get(selectedIndex);

        int arrowCol = left + 2 + Math.max(0, selected.depth()) * 2 - hScroll;
        boolean arrowVisible = arrowCol >= left + 1 && arrowCol < left + 1 + layout.labelWidth();
        boolean arrowClick = selected.directory() && arrowVisible && mouseCol == arrowCol;
        boolean doubleClick = clicked == lastClickIndex && nowNanos - lastClickAtNanos <= doubleClickWindowNanos;
        lastClickIndex = clicked;
        lastClickAtNanos = nowNanos;

        return new ClickResult(true, arrowClick, doubleClick, selected);
    }

    public void resetClickTracking() {
        lastClickIndex = -1;
        lastClickAtNanos = 0L;
    }

    public boolean consumeMetadataChanged() {
        if (!metadataChanged) {
            return false;
        }
        metadataChanged = false;
        return true;
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
        DrawLayout layout = computeLayout(left, right, listRows);
        normalizeSelection();
        clampScroll(layout.textRows());

        int labelWidth = layout.labelWidth();
        int textRows = layout.textRows();
        int sizeWidth = layout.sizeWidth();
        int dateWidth = layout.dateWidth();
        int maxHScroll = Math.max(0, maxLabelLength() - labelWidth);
        hScroll = Math.max(0, Math.min(hScroll, maxHScroll));

        for (int i = 0; i < textRows; i++) {
            int itemIndex = scroll + i;
            int row = listStartRow + i;
            Style style = itemIndex == selectedIndex
                ? (colorEnabled ? selectedColorStyle : selectedMonoStyle)
                : normalStyle;
            String line = rowText(itemIndex, labelWidth, sizeWidth, dateWidth);
            drawLine(buffer, row, left + 1, line, style, cols);
        }
        if (layout.showVScrollbar() && !items.isEmpty()) {
            VerticalScrollbar.draw(
                buffer,
                listStartRow,
                right - 1,
                textRows,
                items.size(),
                scroll,
                "│",
                scrollTrackStyle,
                "█",
                scrollThumbStyle
            );
        }
        if (layout.showHScrollbar() && labelWidth > 0) {
            HorizontalScrollbar.draw(
                buffer,
                listStartRow + textRows,
                left + 1,
                labelWidth,
                Math.max(labelWidth, maxLabelLength()),
                hScroll,
                "─",
                scrollTrackStyle,
                "█",
                scrollThumbStyle
            );
        }
    }

    private void ensureVisible(int listRows) {
        int rows = Math.max(1, listRows);
        int maxScroll = maxScrollForRows(rows);
        if (selectedIndex < scroll) {
            scroll = selectedIndex;
        }
        int lastVisible = scroll + rows - 1;
        if (selectedIndex > lastVisible) {
            scroll = selectedIndex - rows + 1;
        }
        scroll = Math.max(0, Math.min(scroll, maxScroll));
    }

    private void normalizeSelection() {
        if (items.isEmpty()) {
            selectedIndex = 0;
            scroll = 0;
            hScroll = 0;
            return;
        }
        selectedIndex = Math.max(0, Math.min(selectedIndex, items.size() - 1));
    }

    private void clampScroll(int visibleRows) {
        int rows = Math.max(1, visibleRows);
        scroll = Math.max(0, Math.min(scroll, maxScrollForRows(rows)));
    }

    private int maxScrollForRows(int visibleRows) {
        int rows = Math.max(1, visibleRows);
        return Math.max(0, items.size() - rows);
    }

    private int maxHorizontalScroll(int visibleTextWidth) {
        return Math.max(0, maxLabelLength() - Math.max(0, visibleTextWidth));
    }

    private DrawLayout computeLayout(int left, int right, int listRows) {
        int innerWidth = Math.max(0, right - left - 1);
        boolean canShowVScrollbar = innerWidth >= 4;
        int labelMax = maxLabelLength();
        boolean showVScrollbar = false;
        boolean showHScrollbar;
        int textRows;
        int textWidth;
        int labelWidth;
        int sizeWidth;
        int dateWidth;

        for (int i = 0; i < 3; i++) {
            textWidth = Math.max(0, innerWidth - (showVScrollbar ? 1 : 0));
            MetaColumns columns = computeMetaColumns(textWidth);
            labelWidth = columns.labelWidth();
            sizeWidth = columns.sizeWidth();
            dateWidth = columns.dateWidth();
            showHScrollbar = listRows >= 2 && labelMax > labelWidth;
            textRows = Math.max(1, listRows - (showHScrollbar ? 1 : 0));
            boolean needVScrollbar = items.size() > textRows;
            boolean nextShowVScrollbar = canShowVScrollbar && needVScrollbar;
            if (nextShowVScrollbar == showVScrollbar) {
                return new DrawLayout(showVScrollbar, showHScrollbar, textRows, textWidth, labelWidth, sizeWidth, dateWidth);
            }
            showVScrollbar = nextShowVScrollbar;
        }

        textWidth = Math.max(0, innerWidth - (showVScrollbar ? 1 : 0));
        MetaColumns columns = computeMetaColumns(textWidth);
        labelWidth = columns.labelWidth();
        sizeWidth = columns.sizeWidth();
        dateWidth = columns.dateWidth();
        showHScrollbar = listRows >= 2 && labelMax > labelWidth;
        textRows = Math.max(1, listRows - (showHScrollbar ? 1 : 0));
        return new DrawLayout(showVScrollbar, showHScrollbar, textRows, textWidth, labelWidth, sizeWidth, dateWidth);
    }

    private MetaColumns computeMetaColumns(int textWidth) {
        if (textWidth <= 0) {
            return new MetaColumns(0, 0, 0);
        }
        int sizeWidth = showSizeColumn ? DEFAULT_SIZE_COLUMN_WIDTH : 0;
        int dateWidth = showDateColumn ? DATE_COLUMN_WIDTH : 0;
        int separators = (sizeWidth > 0 ? 1 : 0) + (dateWidth > 0 ? 2 : 0);
        int labelWidth = textWidth - sizeWidth - dateWidth - separators;

        if ((sizeWidth > 0 || dateWidth > 0) && labelWidth < MIN_LABEL_WIDTH_WITH_META && sizeWidth > 0) {
            int need = MIN_LABEL_WIDTH_WITH_META - labelWidth;
            int shrink = Math.min(need, Math.max(0, sizeWidth - MIN_SIZE_COLUMN_WIDTH));
            sizeWidth -= shrink;
            labelWidth += shrink;
        }
        if ((sizeWidth > 0 || dateWidth > 0) && labelWidth < MIN_LABEL_WIDTH_WITH_META && dateWidth > 0) {
            labelWidth += dateWidth + 2;
            dateWidth = 0;
        }
        if ((sizeWidth > 0 || dateWidth > 0) && labelWidth < MIN_LABEL_WIDTH_WITH_META && sizeWidth > 0) {
            labelWidth += sizeWidth + 1;
            sizeWidth = 0;
        }

        separators = (sizeWidth > 0 ? 1 : 0) + (dateWidth > 0 ? 2 : 0);
        labelWidth = Math.max(0, textWidth - sizeWidth - dateWidth - separators);
        return new MetaColumns(labelWidth, sizeWidth, dateWidth);
    }

    private int maxLabelLength() {
        return maxLabelLength;
    }

    private void rebuild() {
        List<Item> out = new ArrayList<>();
        Path parent = root.getParent();
        if (parent != null) {
            out.add(new Item(parent, "..", true, false, true, 0, pendingSizeValue(), pendingDateValue()));
        }
        appendTreeItems(root, 0, out);
        items = out;
        maxLabelLength = computeMaxLabelLength(out);
        startMetadataEnrichment(out);
    }

    private void restoreSelection(Path selectedPath) {
        if (selectedPath == null) {
            selectedIndex = Math.min(selectedIndex, Math.max(0, items.size() - 1));
            return;
        }
        int restored = findIndexByPath(selectedPath);
        if (restored >= 0) {
            selectedIndex = restored;
        } else {
            selectedIndex = Math.min(selectedIndex, Math.max(0, items.size() - 1));
        }
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
            out.add(new Item(directory, "<cannot read directory: " + e.getMessage() + ">", false, false, false, depth, "", ""));
            return;
        }

        for (Path child : children) {
            boolean dir = Files.isDirectory(child);
            Path normalized = child.toAbsolutePath().normalize();
            boolean expanded = dir && expandedDirs.contains(normalized);
            String label = formatTreeLabel(depth, child, dir, expanded);
            out.add(new Item(child, label, dir, expanded, false, depth, pendingSizeValue(), pendingDateValue()));
            if (dir && expanded) {
                appendTreeItems(child, depth + 1, out);
            }
        }
    }

    private void startMetadataEnrichment(List<Item> snapshot) {
        long generation;
        Thread previous;
        synchronized (metadataLock) {
            metadataGeneration++;
            generation = metadataGeneration;
            previous = metadataWorker;
            metadataWorker = null;
        }
        if (previous != null) {
            previous.interrupt();
        }
        if ((!showSizeColumn && !showDateColumn) || snapshot.isEmpty()) {
            return;
        }
        Thread worker = new Thread(
            () -> enrichMetadata(snapshot, generation),
            "tuink-tree-meta-loader"
        );
        worker.setDaemon(true);
        synchronized (metadataLock) {
            if (metadataGeneration != generation) {
                return;
            }
            metadataWorker = worker;
        }
        worker.start();
    }

    private void enrichMetadata(List<Item> snapshot, long generation) {
        int changedBatch = 0;
        for (Item item : snapshot) {
            if (Thread.currentThread().isInterrupted() || !isCurrentMetadataGeneration(generation)) {
                return;
            }
            if (item.parentLink() || item.label().startsWith("<cannot read directory:")) {
                continue;
            }
            String size = buildSizeValue(item.path(), item.directory());
            String date = buildDateValue(item.path());
            if (item.updateMetadata(size, date)) {
                changedBatch++;
                if (changedBatch >= 16) {
                    metadataChanged = true;
                    changedBatch = 0;
                }
            }
        }
        if (changedBatch > 0) {
            metadataChanged = true;
        }
    }

    private boolean isCurrentMetadataGeneration(long generation) {
        synchronized (metadataLock) {
            return metadataGeneration == generation;
        }
    }

    private String pendingSizeValue() {
        return showSizeColumn ? PENDING_META : "";
    }

    private String pendingDateValue() {
        return showDateColumn ? PENDING_META : "";
    }

    private List<Path> listDirectorySorted(Path directory) throws IOException {
        try (var stream = Files.list(directory)) {
            return stream
                .filter(this::isVisiblePath)
                .sorted(
                    Comparator
                        .comparing((Path p) -> !Files.isDirectory(p))
                        .thenComparing(p -> p.getFileName().toString().toLowerCase(Locale.ROOT))
                )
                .toList();
        }
    }

    private boolean isVisiblePath(Path path) {
        if (showHiddenFiles) {
            return true;
        }
        String name = path.getFileName() == null ? path.toString() : path.getFileName().toString();
        if (name.startsWith(".")) {
            return false;
        }
        try {
            return !Files.isHidden(path);
        } catch (IOException ignored) {
            return true;
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

    private String rowText(int itemIndex, int labelWidth, int sizeWidth, int dateWidth) {
        boolean hasItem = itemIndex < items.size();
        String rawLabel = hasItem ? items.get(itemIndex).label() : "";
        String label = clipHorizontal(rawLabel, hScroll, Math.max(0, labelWidth - 1));
        StringBuilder row = new StringBuilder();
        row.append(padRight(" " + label, labelWidth));
        if (sizeWidth > 0) {
            String size = hasItem ? items.get(itemIndex).sizeValue() : "";
            row.append(' ').append(hasItem ? alignRight(size, sizeWidth) : " ".repeat(sizeWidth));
        }
        if (dateWidth > 0) {
            String date = hasItem ? items.get(itemIndex).dateValue() : "";
            row.append(hasItem ? " |" : "  ").append(hasItem ? alignRight(date, dateWidth) : " ".repeat(dateWidth));
        }
        return row.toString();
    }

    private static int computeMaxLabelLength(List<Item> items) {
        int max = 0;
        for (Item item : items) {
            max = Math.max(max, CellText.width(item.label()) + 1);
        }
        return max;
    }

    private String buildDateValue(Path path) {
        if (!showDateColumn) {
            return "";
        }
        try {
            LocalDateTime dateTime = LocalDateTime.ofInstant(Files.getLastModifiedTime(path).toInstant(), ZoneId.systemDefault());
            return DATE_FORMATTER.format(dateTime);
        } catch (IOException | RuntimeException ignored) {
            return UNKNOWN_DATE;
        }
    }

    private String buildSizeValue(Path path, boolean directory) {
        if (!showSizeColumn) {
            return "";
        }
        return directory ? formatDirectoryChildCount(path) : formatFileSize(path);
    }

    private String formatDirectoryChildCount(Path path) {
        try (var stream = Files.list(path)) {
            long count = stream.count();
            return count + " items";
        } catch (IOException | RuntimeException ignored) {
            return "? items";
        }
    }

    private String formatFileSize(Path path) {
        try {
            return humanReadableSize(Files.size(path));
        } catch (IOException | RuntimeException ignored) {
            return "?";
        }
    }

    private String humanReadableSize(long bytes) {
        if (bytes < 1024L) {
            return bytes + "B";
        }
        final String[] units = {"K", "M", "G", "T", "P"};
        double value = bytes;
        int unit = -1;
        while (value >= 1024.0 && unit < units.length - 1) {
            value /= 1024.0;
            unit++;
        }
        String format = value >= 10.0 ? "%.0f%s" : "%.1f%s";
        return String.format(Locale.ROOT, format, value, units[unit]);
    }

    private static void drawLine(CellBuffer buffer, int row, int startCol, String text, Style style, int cols) {
        CellText.draw(buffer, row, startCol, text, style, cols);
    }

    private static String padRight(String text, int width) {
        return CellText.fit(text, width);
    }

    private static String alignRight(String text, int width) {
        if (width <= 0) {
            return "";
        }
        return CellText.alignRight(text, width);
    }

    private static String clipHorizontal(String text, int offset, int width) {
        if (text.isEmpty() || width <= 0) {
            return "";
        }
        return CellText.clip(text, offset, width);
    }

    private record MetaColumns(int labelWidth, int sizeWidth, int dateWidth) {
    }

    private record DrawLayout(
        boolean showVScrollbar,
        boolean showHScrollbar,
        int textRows,
        int textWidth,
        int labelWidth,
        int sizeWidth,
        int dateWidth
    ) {
    }

    public static final class Item {
        private final Path path;
        private final String label;
        private final boolean directory;
        private final boolean expanded;
        private final boolean parentLink;
        private final int depth;
        private volatile String sizeValue;
        private volatile String dateValue;

        Item(
            Path path,
            String label,
            boolean directory,
            boolean expanded,
            boolean parentLink,
            int depth,
            String sizeValue,
            String dateValue
        ) {
            this.path = path;
            this.label = label;
            this.directory = directory;
            this.expanded = expanded;
            this.parentLink = parentLink;
            this.depth = depth;
            this.sizeValue = sizeValue;
            this.dateValue = dateValue;
        }

        public Path path() {
            return path;
        }

        public String label() {
            return label;
        }

        public String sizeValue() {
            return sizeValue;
        }

        public String dateValue() {
            return dateValue;
        }

        public boolean directory() {
            return directory;
        }

        public boolean expanded() {
            return expanded;
        }

        public boolean parentLink() {
            return parentLink;
        }

        public int depth() {
            return depth;
        }

        boolean updateMetadata(String sizeValue, String dateValue) {
            String nextSize = sizeValue == null ? "" : sizeValue;
            String nextDate = dateValue == null ? "" : dateValue;
            boolean changed = false;
            if (!this.sizeValue.equals(nextSize)) {
                this.sizeValue = nextSize;
                changed = true;
            }
            if (!this.dateValue.equals(nextDate)) {
                this.dateValue = nextDate;
                changed = true;
            }
            return changed;
        }
    }

    public record ClickResult(boolean consumed, boolean arrowClick, boolean doubleClick, Item item) {
    }

    public record ScrollPointerResult(boolean consumed, ScrollAxis axis, boolean drag) {
    }

    public enum ScrollAxis {
        NONE,
        VERTICAL,
        HORIZONTAL
    }
}
