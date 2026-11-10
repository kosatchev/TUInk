package dev.tuink.tui;

import dev.tuiframe.cell.Canvas;
import dev.tuiframe.cell.CellText;
import dev.tuiframe.explorer.ui.PathPresentation;
import dev.tuiframe.explorer.file.PathLabels;
import dev.tuiframe.geometry.Rect;
import dev.tuiframe.style.CellStyle;
import dev.tuiframe.text.TerminalPlainText;
import dev.tuiframe.text.TerminalText;
import dev.tuiframe.widget.ControlStyles;
import dev.tuiframe.widget.ListView;
import dev.tuiframe.widget.Theme;
import dev.tuiframe.widget.WidgetState;
import dev.tuiframe.widget.WidgetThemeKeys;
import dev.tuink.project.search.ProjectSearch;
import dev.tuink.storage.jvm.JvmDocumentStorage;
import java.util.ArrayList;
import java.util.List;

/** Snapshots only visible result rows. Source ranges remain UTF-16; painting uses complete display graphemes. */
final class ProjectSearchResults {
    enum Role { TEXT, FILE, MUTED, MATCH, CURRENT }
    record Part(String text, Role role) {}
    record Line(int index, List<Part> parts) { Line { parts = List.copyOf(parts); } }
    private ProjectSearchResults() {}

    static List<Line> snapshot(ProjectSearchPanel panel, ListView.Placement list) {
        ArrayList<Line> result = new ArrayList<>();
        int first = list.state().firstVisibleIndex(), width = list.contentBounds().width();
        if (width <= 0) return List.of();
        int end = Math.min(panel.rows.size(), first + list.contentBounds().height());
        for (int index = first; index < end; index++) {
            var row = panel.rows.get(index);
            var group = panel.groups.get(row.group());
            List<Part> parts = row.match() != null && group != null ? match(row.match(), group.lines.get(row.match().line()), width)
                : group != null ? file(group, panel.collapsed.contains(row.group()), width)
                : List.of(new Part(TerminalPlainText.singleLine(row.text()), Role.TEXT));
            result.add(new Line(index, parts));
        }
        return List.copyOf(result);
    }
    private static List<Part> file(ProjectSearchPanel.Group group, boolean collapsed, int width) {
        String label = TerminalPlainText.singleLine(group.target.label());
        int slash = label.lastIndexOf('/');
        var path = JvmDocumentStorage.pathOf(group.target.resource());
        String name = path == null ? label.substring(slash + 1) : PathLabels.name(path);
        String parent = label.endsWith(name) ? label.substring(0, label.length() - name.length()) : slash < 0 ? "" : label.substring(0, slash + 1);
        String suffix = " (" + group.matches.size() + ")" + (group.stale ? " !" : group.target.documentId() != null ? " *" : "");
        int available = Math.max(1, width - 2 - CellText.width(suffix));
        String fitted = PathPresentation.fit(name, available, available);
        int rest = width - 2 - CellText.width(fitted) - CellText.width(suffix);
        ArrayList<Part> parts = new ArrayList<>();
        parts.add(new Part(collapsed ? "▸ " : "▾ ", Role.MUTED));
        parts.add(new Part(fitted, Role.FILE)); parts.add(new Part(suffix, Role.MUTED));
        if (!parent.isEmpty() && rest >= 4) parts.add(new Part(" " + PathPresentation.fit(parent, rest - 1, 4), Role.MUTED));
        return parts;
    }
    private static List<Part> match(ProjectSearch.Match current, List<ProjectSearch.Match> ranges, int width) {
        ArrayList<Part> parts = new ArrayList<>();
        String prefix = "  " + (current.line() + 1) + "  ";
        parts.add(new Part(prefix, Role.MUTED));
        int available = Math.max(0, width - CellText.width(prefix));
        if (available == 0) return parts;
        int local = Math.max(0, current.start() - current.snippetOffset());
        int context = Math.min(4, Math.max(0, available / 4));
        int wanted = Math.max(0, local - context);
        var cursor = TerminalText.graphemeCursor(current.snippet());
        boolean started = false;
        int column = 0, range = 0, displayedEnd = 0;
        while (column < available && cursor.advance()) {
            if (!started && cursor.end() <= wanted) continue;
            if (!started) {
                started = true;
                range = firstRange(ranges, current.snippetOffset() + cursor.start());
                if (current.snippetOffset() + cursor.start() > 0) { parts.add(new Part("…", Role.MUTED)); column++; }
            }
            int from = current.snippetOffset() + cursor.start(), to = current.snippetOffset() + cursor.end();
            while (range < ranges.size() && (ranges.get(range).end() < from
                || ranges.get(range).end() == from && ranges.get(range).start() != ranges.get(range).end())) range++;
            Role role = Role.TEXT;
            boolean zero = false, currentZero = false;
            for (int i = range; i < ranges.size() && ranges.get(i).start() < to; i++) {
                var candidate = ranges.get(i);
                if (candidate.start() == candidate.end()) {
                    if (candidate.start() >= from) { zero = true; currentZero |= candidate == current; }
                } else if (candidate.end() > from) {
                    if (candidate == current) role = Role.CURRENT;
                    else if (role != Role.CURRENT) role = Role.MATCH;
                }
            }
            if (zero && column < available) { parts.add(new Part("▏", currentZero ? Role.CURRENT : Role.MATCH)); column++; }
            String glyph = TerminalPlainText.singleLine(current.snippet().substring(cursor.start(), cursor.end()));
            int cells = CellText.width(glyph);
            if (column + cells > available) break;
            parts.add(new Part(glyph, role)); column += cells; displayedEnd = cursor.end();
        }
        int last = current.snippetOffset() + current.snippet().length();
        if (column < available && displayedEnd == current.snippet().length()) for (int i = firstRange(ranges, last); i < ranges.size() && ranges.get(i).start() <= last; i++) {
            var candidate = ranges.get(i);
            if (candidate.start() == last && candidate.end() == last) {
                parts.add(new Part("▏", candidate == current ? Role.CURRENT : Role.MATCH)); break;
            }
        }
        return parts;
    }
    private static int firstRange(List<ProjectSearch.Match> ranges, int offset) {
        int low = 0, high = ranges.size();
        while (low < high) {
            int mid = (low + high) >>> 1;
            var match = ranges.get(mid);
            if (match.end() < offset || match.end() == offset && match.start() != match.end()) low = mid + 1;
            else high = mid;
        }
        return low;
    }
    static void paint(Canvas canvas, Theme theme, ListView.Placement list, List<Line> lines, boolean focused, boolean enabled) {
        var state = new WidgetState(focused, enabled);
        list.paint(canvas, theme, state);
        var normal = theme.resolveStyle(WidgetThemeKeys.LIST_ITEM, theme.resolveStyle(WidgetThemeKeys.MODAL_BODY));
        if (!enabled) normal = theme.resolveStyle(WidgetThemeKeys.LIST_ITEM_DISABLED, normal);
        var selected = ControlStyles.listSelection(theme, state);
        int mark = canvas.save();
        try {
            if (!canvas.clip(list.contentBounds())) return;
            for (var line : lines) {
                boolean isSelected = line.index() == list.state().selectedIndex();
                var base = isSelected && enabled ? selected : normal;
                int y = list.contentBounds().y() + line.index() - list.state().firstVisibleIndex();
                canvas.fill(new Rect(list.contentBounds().x(), y, list.contentBounds().width(), 1), base);
                int x = list.contentBounds().x(), right = list.contentBounds().rightExclusive();
                for (var part : line.parts()) {
                    var style = style(theme, part.role(), base, isSelected, focused, enabled);
                    CellText.drawClipped(canvas, x, y, part.text(), style, Math.max(0, right - x));
                    x += CellText.width(part.text());
                    if (x >= right) break;
                }
            }
        } finally { canvas.restore(mark); }
    }
    private static CellStyle style(Theme theme, Role role, CellStyle base, boolean selected, boolean focused, boolean enabled) {
        if (!enabled) return base;
        if (selected && !focused) return base.emphasis(base.bold(),
            base.underline() || role == Role.MATCH || role == Role.CURRENT);
        if (role == Role.MATCH || role == Role.CURRENT) {
            var key = role == Role.CURRENT && selected && focused ? WidgetThemeKeys.SEARCH_CURRENT_MATCH : WidgetThemeKeys.SEARCH_MATCH;
            return theme.resolveStyle(key, new CellStyle(base.fg(), base.bg(), true, true));
        }
        var foreground = role == Role.FILE ? theme.resolveStyle(WidgetThemeKeys.SEARCH_FILE, base)
            : role == Role.MUTED ? theme.resolveStyle(WidgetThemeKeys.TEXT_MUTED, base) : base;
        return new CellStyle(selected ? base.fg() : foreground.fg(), base.bg(), (!selected && role == Role.FILE) || base.bold(), base.underline(), base.inverse());
    }
}
