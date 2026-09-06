package dev.tuink.tui.ui;

import dev.tuink.tui.file.FileTreeModel;
import dev.tuink.terminal.cell.CellBuffer;
import dev.tuink.terminal.cell.CellText;
import dev.tuink.terminal.cell.Canvas;
import dev.tuink.terminal.geometry.Rect;
import dev.tuink.terminal.scroll.ScrollInteraction;
import dev.tuink.terminal.style.CellStyle;
import dev.tuink.terminal.scroll.HorizontalScrollbar;
import dev.tuink.terminal.scroll.VerticalScrollbar;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/** TUInk open-file tree facade for selection, layout, painting and pointer interaction. */
public final class TreeView implements AutoCloseable {
    private static final int MIN_LABEL_WIDTH_WITH_META = 12;
    private static final int DEFAULT_SIZE_COLUMN_WIDTH = 11;
    private static final int MIN_SIZE_COLUMN_WIDTH = 7;
    private static final int DATE_COLUMN_WIDTH = 14;
    private final FileTreeModel model;
    private List<Item> items = List.of();
    private int maxLabelLength;
    private int selectedIndex;
    private int scroll;
    private int hScroll;
    private int lastClickIndex = -1;
    private long lastClickAtNanos;

    public TreeView(Path root) {
        this(root, false, false);
    }

    public TreeView(Path root, boolean showSizeColumn, boolean showDateColumn) {
        model = new FileTreeModel(root, showSizeColumn, showDateColumn);
        refreshItems();
        selectedIndex = 0;
        scroll = 0;
        hScroll = 0;
        resetClickTracking();
    }

    public Path root() {
        return model.root();
    }

    public int selectedIndex() {
        return selectedIndex;
    }

    public int scrollOffset() {
        return scroll;
    }

    public boolean showHiddenFiles() {
        return model.showHiddenFiles();
    }

    public void setShowHiddenFiles(boolean enabled) {
        if (model.showHiddenFiles() == enabled) {
            return;
        }
        FileTreeModel.EntryId selectedId = selectedItem() == null ? null : selectedItem().id();
        model.setShowHiddenFiles(enabled);
        refreshItems();
        restoreSelection(selectedId);
        scroll = Math.min(scroll, Math.max(0, items.size() - 1));
    }

    public void setShowSizeColumn(boolean enabled) {
        if (model.showSizeColumn() == enabled) {
            return;
        }
        FileTreeModel.EntryId selectedId = selectedItem() == null ? null : selectedItem().id();
        model.setShowSizeColumn(enabled);
        refreshItems();
        restoreSelection(selectedId);
    }

    public void setShowDateColumn(boolean enabled) {
        if (model.showDateColumn() == enabled) {
            return;
        }
        FileTreeModel.EntryId selectedId = selectedItem() == null ? null : selectedItem().id();
        model.setShowDateColumn(enabled);
        refreshItems();
        restoreSelection(selectedId);
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
        model.setRoot(root);
        refreshItems();
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
        Path parent = model.root().getParent();
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
        FileTreeModel.EntryId selectedId = selected.id();
        if (!model.toggleDirectory(selectedId)) {
            return false;
        }
        refreshItems();
        restoreSelection(selectedId);
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
        if (!model.consumeMetadataChanged()) {
            return false;
        }
        FileTreeModel.EntryId selectedId = selectedItem() == null ? null : selectedItem().id();
        refreshItems();
        restoreSelection(selectedId);
        return true;
    }

    @Override
    public void close() {
        model.close();
    }

    public void draw(
        CellBuffer buffer,
        int cols,
        int left,
        int right,
        int listStartRow,
        int listRows,
        boolean colorEnabled,
        CellStyle normalStyle,
        CellStyle selectedColorStyle,
        CellStyle selectedMonoStyle,
        CellStyle scrollTrackStyle,
        CellStyle scrollThumbStyle
    ) {
        Objects.requireNonNull(buffer, "buffer");
        draw(
            new Canvas(buffer),
            buffer.rows(),
            cols,
            left,
            right,
            listStartRow,
            listRows,
            colorEnabled,
            normalStyle,
            selectedColorStyle,
            selectedMonoStyle,
            scrollTrackStyle,
            scrollThumbStyle
        );
    }

    /**
     * Draws list rows and scrollbars into one caller-owned Canvas. The declared
     * frame and list-text geometry are strict clips, and caller state is always
     * restored.
     */
    public void draw(
        Canvas canvas,
        int rows,
        int cols,
        int left,
        int right,
        int listStartRow,
        int listRows,
        boolean colorEnabled,
        CellStyle normalStyle,
        CellStyle selectedColorStyle,
        CellStyle selectedMonoStyle,
        CellStyle scrollTrackStyle,
        CellStyle scrollThumbStyle
    ) {
        Objects.requireNonNull(canvas, "canvas");
        DrawLayout layout = computeLayout(left, right, listRows);
        normalizeSelection();
        clampScroll(layout.textRows());

        int labelWidth = layout.labelWidth();
        int textRows = layout.textRows();
        int sizeWidth = layout.sizeWidth();
        int dateWidth = layout.dateWidth();
        int maxHScroll = Math.max(0, maxLabelLength() - labelWidth);
        hScroll = Math.max(0, Math.min(hScroll, maxHScroll));

        int mark = canvas.save();
        try {
            if (!canvas.clip(0, 0, Math.max(0, cols), Math.max(0, rows)) || listRows <= 0) {
                return;
            }
            drawTextRows(
                canvas,
                left,
                listStartRow,
                layout.textWidth(),
                textRows,
                labelWidth,
                sizeWidth,
                dateWidth,
                colorEnabled,
                normalStyle,
                selectedColorStyle,
                selectedMonoStyle
            );
            if (layout.showVScrollbar() && !items.isEmpty()) {
                VerticalScrollbar.draw(
                    canvas,
                    new Rect(right - 1, listStartRow, 1, textRows),
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
                    canvas,
                    new Rect(left + 1, listStartRow + textRows, labelWidth, 1),
                    Math.max(labelWidth, maxLabelLength()),
                    hScroll,
                    "─",
                    scrollTrackStyle,
                    "█",
                    scrollThumbStyle
                );
            }
        } finally {
            canvas.restore(mark);
        }
    }

    private void drawTextRows(
        Canvas canvas,
        int left,
        int listStartRow,
        int textWidth,
        int textRows,
        int labelWidth,
        int sizeWidth,
        int dateWidth,
        boolean colorEnabled,
        CellStyle normalStyle,
        CellStyle selectedColorStyle,
        CellStyle selectedMonoStyle
    ) {
        if (textWidth <= 0 || textRows <= 0) {
            return;
        }
        int mark = canvas.save();
        try {
            if (!canvas.clip(left + 1, listStartRow, textWidth, textRows)) {
                return;
            }
            for (int i = 0; i < textRows; i++) {
                int itemIndex = scroll + i;
                int row = listStartRow + i;
                CellStyle style = itemIndex == selectedIndex
                    ? (colorEnabled ? selectedColorStyle : selectedMonoStyle)
                    : normalStyle;
                String line = rowText(itemIndex, labelWidth, sizeWidth, dateWidth);
                CellText.draw(canvas, left + 1, row, line, style);
            }
        } finally {
            canvas.restore(mark);
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
        int sizeWidth = model.showSizeColumn() ? DEFAULT_SIZE_COLUMN_WIDTH : 0;
        int dateWidth = model.showDateColumn() ? DATE_COLUMN_WIDTH : 0;
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

    private void refreshItems() {
        items = model.snapshot().entries().stream()
            .map(entry -> new Item(entry, formatTreeLabel(entry)))
            .toList();
        maxLabelLength = computeMaxLabelLength(items);
    }

    private void restoreSelection(FileTreeModel.EntryId selectedId) {
        if (selectedId == null) {
            selectedIndex = Math.min(selectedIndex, Math.max(0, items.size() - 1));
            return;
        }
        int restored = findIndexById(selectedId);
        if (restored >= 0) {
            selectedIndex = restored;
        } else {
            selectedIndex = Math.min(selectedIndex, Math.max(0, items.size() - 1));
        }
    }

    private int findIndexById(FileTreeModel.EntryId id) {
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).id().equals(id)) {
                return i;
            }
        }
        return -1;
    }

    private static String formatTreeLabel(FileTreeModel.Entry entry) {
        if (entry.kind() == FileTreeModel.Kind.PARENT || entry.kind() == FileTreeModel.Kind.ERROR) {
            return entry.name();
        }
        String indent = "  ".repeat(entry.depth());
        if (entry.kind() == FileTreeModel.Kind.FILE) {
            return indent + "  " + entry.name();
        }
        return indent + (entry.expanded() ? "▼ " : "▶ ") + entry.name() + "/";
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
        private final FileTreeModel.Entry entry;
        private final String label;

        Item(FileTreeModel.Entry entry, String label) {
            this.entry = Objects.requireNonNull(entry, "entry");
            this.label = label;
        }

        private FileTreeModel.EntryId id() {
            return entry.id();
        }

        public Path path() {
            return entry.path();
        }

        public String label() {
            return label;
        }

        public String sizeValue() {
            return entry.sizeValue();
        }

        public String dateValue() {
            return entry.dateValue();
        }

        public boolean directory() {
            return entry.directory();
        }

        public boolean expanded() {
            return entry.expanded();
        }

        public boolean parentLink() {
            return entry.kind() == FileTreeModel.Kind.PARENT;
        }

        public int depth() {
            return entry.depth();
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
