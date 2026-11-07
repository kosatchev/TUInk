package dev.tuiframe.explorer.ui;

import dev.tuiframe.cell.CellText;
import dev.tuiframe.explorer.file.PathLabels;
import dev.tuiframe.text.TerminalText;
import dev.tuiframe.widget.LabelPolicy;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Lossless path labels. A basename beginning {@code file:} is an encoded display label, not an input path. */
public final class PathPresentation {
    private static final String URI_MARKER = "file:";

    /** Widget clipping keeps the marker and percent escapes atomic; ordinary labels retain their geometry. */
    public static final LabelPolicy LABEL_POLICY = new LabelPolicy() {
        @Override
        public CellText.FittedText fitMiddle(String text, int cells, int preferredTailCells, int initialColumn) {
            if (preferredTailCells < 0 || initialColumn < 0) {
                throw new IllegalArgumentException("Text fitting bounds must be non-negative");
            }
            if (!hasTokens(text) || text.indexOf('\t') >= 0) {
                return CellText.fitMiddle(text, cells, preferredTailCells, initialColumn);
            }
            if (cells <= 0) return new CellText.FittedText("", text.isEmpty());
            if (CellText.width(text) <= cells) return new CellText.FittedText(text, true);
            int markerCells = text.startsWith(URI_MARKER) ? URI_MARKER.length() : 0;
            if (cells <= markerCells || cells == 1) return new CellText.FittedText("…", false);
            List<Part> parts = parts(text);
            int tailBudget = Math.min(Math.max(cells / 2, Math.min(preferredTailCells, cells - 1)),
                cells - 1 - markerCells);
            int tailStart = parts.size();
            int tailWidth = 0;
            while (tailStart > 0 && tailWidth + parts.get(tailStart - 1).cells() <= tailBudget) {
                tailWidth += parts.get(--tailStart).cells();
            }
            int headBudget = cells - 1 - tailWidth;
            StringBuilder result = new StringBuilder();
            int headWidth = 0;
            for (int index = 0; index < tailStart && headWidth + parts.get(index).cells() <= headBudget; index++) {
                Part part = parts.get(index);
                result.append(part.text());
                headWidth += part.cells();
            }
            result.append('…');
            for (int index = tailStart; index < parts.size(); index++) result.append(parts.get(index).text());
            return new CellText.FittedText(result.toString(), false);
        }

        @Override
        public String clip(String text, int leftCells, int cells) {
            if (!hasTokens(text)) return CellText.clip(text, leftCells, cells);
            if (leftCells < 0 || cells <= 0) return "";
            long right = (long) leftCells + cells;
            int visual = 0;
            StringBuilder result = new StringBuilder();
            for (Part part : parts(text)) {
                int start = visual;
                visual += part.cells();
                if (visual <= leftCells) continue;
                if (start >= right) break;
                if (start >= leftCells && visual <= right) result.append(part.text());
                else result.append(" ".repeat((int) (Math.min(visual, right) - Math.max(start, leftCells))));
            }
            return result.toString();
        }
    };

    private PathPresentation() {}

    /** Full, reversible input spelling, using a URI when terminal sanitization would change its identity. */
    public static String full(Path path) {
        return PathLabels.full(path);
    }

    /** Uses the original path's URI component, so a raw basename never acquires a fictitious working directory. */
    public static String name(Path path) {
        return PathLabels.name(path);
    }

    public static boolean encodedName(Path path) {
        return PathLabels.encodedName(path);
    }

    public static String fit(String text, int cells, int preferredTailCells) {
        return LABEL_POLICY.fitMiddle(text, cells, preferredTailCells, 0).text();
    }

    public static String clip(String text, int leftCells, int cells) {
        return LABEL_POLICY.clip(text, leftCells, cells);
    }

    private static boolean hasTokens(String text) {
        if (text == null) return false;
        for (int index = 0; index < text.length(); index++) {
            if (tokenEnd(text, index) > index + 1) return true;
        }
        return false;
    }

    private static int tokenEnd(String text, int offset) {
        if (text.startsWith(URI_MARKER, offset)) return offset + URI_MARKER.length();
        return text.charAt(offset) == '%' && offset + 2 < text.length()
            && hex(text.charAt(offset + 1)) && hex(text.charAt(offset + 2))
            ? offset + 3 : offset + 1;
    }

    private static boolean hex(char character) {
        return character >= '0' && character <= '9' || character >= 'a' && character <= 'f'
            || character >= 'A' && character <= 'F';
    }

    private static List<Part> parts(String text) {
        List<TerminalText.Grapheme> graphemes = TerminalText.graphemes(text);
        List<Part> result = new ArrayList<>();
        for (int index = 0; index < graphemes.size();) {
            int start = graphemes.get(index).start();
            int end = Math.max(graphemes.get(index).end(), tokenEnd(text, start));
            int next = index + 1;
            while (next < graphemes.size() && graphemes.get(next).start() < end) {
                end = Math.max(end, graphemes.get(next++).end());
            }
            String part = text.substring(start, end);
            result.add(new Part(part, CellText.width(part)));
            index = next;
        }
        return result;
    }

    private record Part(String text, int cells) {}
}
