package dev.tuiframe.widget;

import dev.tuiframe.cell.Canvas;
import dev.tuiframe.cell.CellText;
import dev.tuiframe.geometry.Rect;
import dev.tuiframe.text.TerminalPlainText;
import java.util.List;
import java.util.Objects;

/** Passive single-row path display. The owner supplies labels, never filesystem operations. */
public final class PathBar {
    private final String root;
    private final List<String> segments;
    private final String separator;
    private final String suffix;
    private final LabelPolicy labels;

    /**
     * @param root display prefix including its trailing separator, or empty for a relative path
     * @param segments directory labels followed by the filename; never interpreted as filesystem paths
     * @param labels atomic boundaries for fitting labels, such as encoded URI tokens
     */
    public PathBar(String root, List<String> segments, String separator, String suffix, LabelPolicy labels) {
        this.root = safe(root);
        this.segments = Objects.requireNonNull(segments).stream().map(PathBar::safe).toList();
        this.separator = safe(separator);
        this.suffix = safe(suffix);
        this.labels = Objects.requireNonNull(labels);
        if (separator.isEmpty()) throw new IllegalArgumentException("Path separator must not be empty");
    }

    public PathBar(String root, List<String> segments, String separator) {
        this(root, segments, separator, "", LabelPolicy.DEFAULT);
    }

    private static String safe(String value) { return TerminalPlainText.singleLine(Objects.requireNonNull(value)); }

    public String text() { return root + String.join(separator, segments) + suffix; }

    /** Collapse whole directories first; only the final fallback elides the filename itself. */
    public String fit(int cells) {
        if (cells <= 0) return "";
        String full = text();
        if (CellText.width(full) <= cells) return full;
        if (segments.isEmpty()) return labels.fitMiddle(full, cells, cells / 2, 0).text();
        int count = segments.size();
        if (root.isEmpty() && count > 2) {
            String candidate = fitTail(segments.getFirst() + separator + "…" + separator, count - 2, cells);
            if (candidate != null) return candidate;
        }
        String candidate = fitTail(root + "…" + separator, count - 1, cells);
        if (candidate != null) return candidate;
        String leaf = segments.getLast();
        int dot = leaf.lastIndexOf('.');
        int extension = dot > 0 ? CellText.width(leaf.substring(dot)) : 0;
        String prefix = count > 1 ? root + "…" + separator : root;
        if (count > 1) {
            String withParent = (count > 2 ? root + "…" + separator : root)
                + segments.get(count - 2) + separator;
            if (CellText.width(withParent + suffix) + Math.max(8, extension + 3) <= cells) prefix = withParent;
        }
        if (CellText.width(prefix + suffix) + 3 > cells) prefix = root;
        if (CellText.width(prefix + suffix) + 1 > cells) prefix = "";
        int remaining = cells - CellText.width(prefix + suffix);
        if (remaining < 1) return labels.fitMiddle(leaf + suffix, cells, cells / 2, 0).text();
        int tailCells = Math.min(Math.max(extension, remaining / 2), Math.max(0, remaining - 2));
        return prefix + labels.fitMiddle(leaf, remaining, tailCells, CellText.width(prefix)).text() + suffix;
    }

    private String fitTail(String prefix, int maximum, int cells) {
        String fitted = null;
        // Grow only the visible tail. Deep paths must not repeatedly join and measure hidden directories.
        for (int count = 1; count <= maximum; count++) {
            String candidate = prefix + tail(count) + suffix;
            if (CellText.width(candidate) > cells) break;
            fitted = candidate;
        }
        return fitted;
    }

    private String tail(int count) { return String.join(separator, segments.subList(segments.size() - count, segments.size())); }

    public Placement layout(Rect bounds) {
        Objects.requireNonNull(bounds);
        Rect row = new Rect(bounds.x(), bounds.y(), bounds.width(), Math.min(1, bounds.height()));
        return new Placement(row, fit(row.width()));
    }

    public record Placement(Rect bounds, String text) implements PlacedWidget {
        public Placement { Objects.requireNonNull(bounds); Objects.requireNonNull(text); }
        @Override public void paint(Canvas canvas, Theme theme, WidgetState state) {
            if (bounds.height() == 0) return;
            var style = theme.resolveStyle(WidgetThemeKeys.PATH_BAR, theme.resolveStyle(WidgetThemeKeys.MODAL_BODY));
            int mark = canvas.save();
            try {
                canvas.clip(bounds);
                canvas.fill(bounds, style);
                CellText.drawClipped(canvas, bounds.x(), bounds.y(), text, style, bounds.width());
            } finally { canvas.restore(mark); }
        }
    }
}
