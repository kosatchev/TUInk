package dev.tuiframe.jline.presentation;

import dev.tuiframe.capability.TerminalCapabilities;
import dev.tuiframe.cell.Cell;
import dev.tuiframe.cell.CellBuffer;
import dev.tuiframe.geometry.Rect;
import dev.tuiframe.screen.ScrollHint;
import dev.tuiframe.style.CellColor;
import dev.tuiframe.style.CellStyle;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Produces ANSI output for differences between two cell buffers.
 */
final class AnsiDiff {
    private final Map<CellStyle, String> colorStyleCache = new HashMap<>();
    private final Map<CellStyle, String> monoStyleCache = new HashMap<>();

    AnsiDiff() {
    }

    String diff(
        CellBuffer previous,
        CellBuffer current,
        boolean colorEnabled,
        ScrollHint scrollHint
    ) {
        return diff(
            previous,
            current,
            TerminalCapabilities.DEFAULT.withColorEnabled(colorEnabled),
            scrollHint
        );
    }

    String diff(
        CellBuffer previous,
        CellBuffer current,
        TerminalCapabilities capabilities,
        ScrollHint scrollHint
    ) {
        Objects.requireNonNull(capabilities, "capabilities");
        boolean colorEnabled = capabilities.colorEnabled();
        ScrollHint hint = scrollHint == null ? ScrollHint.none() : scrollHint;
        Rect scrollRegion = hint instanceof ScrollHint.Vertical vertical
            ? vertical.region()
            : hint instanceof ScrollHint.Horizontal horizontal
                ? horizontal.region()
                : new Rect(0, 0, current.cols(), 0);
        int scrollRegionTop = scrollRegion.y();
        int scrollRegionHeight = scrollRegion.height();
        int textStartCol = scrollRegion.x();
        int textEndCol = scrollRegion.width() == 0
            ? current.cols() - 1
            : scrollRegion.rightExclusive() - 1;
        boolean enableVerticalScrollFastPath = capabilities.verticalScroll()
            && hint instanceof ScrollHint.Vertical;
        boolean enableHorizontalScrollFastPath = capabilities.horizontalScroll()
            && hint instanceof ScrollHint.Horizontal;
        int horizontalDeltaColumns = hint instanceof ScrollHint.Horizontal horizontal
            ? horizontal.deltaColumns()
            : 0;
        StringBuilder out = new StringBuilder();
        String activeStyleAnsi = null;
        boolean wroteAnyCell = false;

        if (previous == null || previous.rows() != current.rows() || previous.cols() != current.cols()) {
            out.append("\u001B[H");
            for (int r = 0; r < current.rows(); r++) {
                moveTo(out, r, 0);
                for (int c = 0; c < current.cols(); c++) {
                    Cell cell = current.get(r, c);
                    if (cell.continuation()) {
                        continue;
                    }
                    String styleAnsi = ansiFor(cell.style(), colorEnabled);
                    if (!styleAnsi.equals(activeStyleAnsi)) {
                        out.append(styleAnsi);
                        activeStyleAnsi = styleAnsi;
                    }
                    out.append(cell.grapheme());
                    wroteAnyCell = true;
                }
            }
            if (wroteAnyCell) {
                out.append("\u001B[0m");
            }
            return out.toString();
        }

        int safeTextStart = Math.max(0, Math.min(textStartCol, current.cols() - 1));
        int safeTextEnd = Math.max(safeTextStart, Math.min(textEndCol, current.cols() - 1));
        int regionTop = Math.max(0, scrollRegionTop);
        int regionBottom = Math.min(current.rows() - 1, regionTop + Math.max(0, scrollRegionHeight) - 1);
        boolean horizontalApplied = false;
        if (enableHorizontalScrollFastPath
            && horizontalDeltaColumns != 0
            && scrollRegionTop >= 0
            && regionBottom >= regionTop
            && safeTextEnd >= safeTextStart
            && !previous.regionHasWideCells(regionTop, regionBottom, safeTextStart, safeTextEnd)
            && !current.regionHasWideCells(regionTop, regionBottom, safeTextStart, safeTextEnd)
            && horizontalOverlapMatches(
                previous,
                current,
                regionTop,
                regionBottom,
                safeTextStart,
                safeTextEnd,
                horizontalDeltaColumns
            )) {
            activeStyleAnsi = applyHorizontalScroll(
                out,
                current,
                colorEnabled,
                activeStyleAnsi,
                regionTop,
                regionBottom,
                safeTextStart,
                safeTextEnd,
                horizontalDeltaColumns
            );
            wroteAnyCell = true;
            horizontalApplied = true;
        }

        VerticalScroll scroll = null;
        if (!horizontalApplied && enableVerticalScrollFastPath) {
            scroll = detectVerticalScroll(
                previous,
                current,
                scrollRegionTop,
                scrollRegionHeight,
                safeTextStart,
                safeTextEnd
            );
            if (scroll != null) {
                applyVerticalScroll(out, scroll);
                activeStyleAnsi = drawEnteringRows(out, current, colorEnabled, activeStyleAnsi, scroll);
                wroteAnyCell = scroll.enteringCount() > 0;
            }
        }

        for (int r = 0; r < current.rows(); r++) {
            if (horizontalApplied && r >= regionTop && r <= regionBottom) {
                activeStyleAnsi = drawRowRange(
                    current,
                    colorEnabled,
                    r,
                    0,
                    safeTextStart - 1,
                    out,
                    activeStyleAnsi
                );
                activeStyleAnsi = drawRowRange(
                    current,
                    colorEnabled,
                    r,
                    safeTextEnd + 1,
                    current.cols() - 1,
                    out,
                    activeStyleAnsi
                );
                wroteAnyCell = true;
                continue;
            }
            if (scroll != null && scroll.skipRow(r)) {
                continue;
            }
            if (scroll != null && scroll.matchedRows()[r]) {
                int shiftedPrevRow = r + scroll.delta();
                RowWrite left = diffRowRange(
                    previous,
                    current,
                    colorEnabled,
                    shiftedPrevRow,
                    r,
                    0,
                    safeTextStart - 1,
                    out,
                    activeStyleAnsi
                );
                wroteAnyCell = left.wroteAny() || wroteAnyCell;
                activeStyleAnsi = left.activeStyleAnsi();
                RowWrite right = diffRowRange(
                    previous,
                    current,
                    colorEnabled,
                    shiftedPrevRow,
                    r,
                    safeTextEnd + 1,
                    current.cols() - 1,
                    out,
                    activeStyleAnsi
                );
                wroteAnyCell = right.wroteAny() || wroteAnyCell;
                activeStyleAnsi = right.activeStyleAnsi();
                continue;
            }
            if (scroll != null && scroll.inRegion(r)) {
                // After terminal scroll command, the physical row r contains previous row (r + delta).
                // For non-matched rows we must diff against that shifted row to avoid stale styles.
                int shiftedPrevRow = r + scroll.delta();
                RowWrite rowWrite = diffRowRange(
                    previous,
                    current,
                    colorEnabled,
                    shiftedPrevRow,
                    r,
                    0,
                    current.cols() - 1,
                    out,
                    activeStyleAnsi
                );
                wroteAnyCell = rowWrite.wroteAny() || wroteAnyCell;
                activeStyleAnsi = rowWrite.activeStyleAnsi();
                continue;
            }
            if (previous.rowSignature1(r) == current.rowSignature1(r)
                && previous.rowSignature2(r) == current.rowSignature2(r)) {
                continue;
            }
            if (previous.rowHasWideCells(r) || current.rowHasWideCells(r)) {
                activeStyleAnsi = drawRowRange(
                    current,
                    colorEnabled,
                    r,
                    0,
                    current.cols() - 1,
                    out,
                    activeStyleAnsi
                );
                wroteAnyCell = true;
                continue;
            }
            int firstDiff = firstDifferentColumn(previous, current, r, 0, current.cols() - 1);
            if (firstDiff < 0) {
                continue;
            }
            int lastDiff = lastDifferentColumn(previous, current, r, firstDiff, current.cols() - 1);
            int c = firstDiff;
            while (c <= lastDiff) {
                while (c <= lastDiff && current.get(r, c).equals(previous.get(r, c))) {
                    c++;
                }
                if (c > lastDiff) {
                    break;
                }
                moveTo(out, r, c);
                while (c <= lastDiff) {
                    Cell next = current.get(r, c);
                    Cell prev = previous.get(r, c);
                    if (next.equals(prev)) {
                        break;
                    }
                    String styleAnsi = ansiFor(next.style(), colorEnabled);
                    if (!styleAnsi.equals(activeStyleAnsi)) {
                        out.append(styleAnsi);
                        activeStyleAnsi = styleAnsi;
                    }
                    if (!next.continuation()) {
                        out.append(next.grapheme());
                    }
                    wroteAnyCell = true;
                    c++;
                }
            }
        }

        if (wroteAnyCell) {
            out.append("\u001B[0m");
        }
        return out.toString();
    }

    private String applyHorizontalScroll(
        StringBuilder out,
        CellBuffer current,
        boolean colorEnabled,
        String activeStyleAnsi,
        int topRow,
        int bottomRow,
        int textStartCol,
        int textEndCol,
        int deltaColumns
    ) {
        int width = textEndCol - textStartCol + 1;
        if (width <= 0) {
            return activeStyleAnsi;
        }
        int shift = (int) Math.min(Math.abs((long) deltaColumns), (long) width);
        if (shift <= 0) {
            return activeStyleAnsi;
        }
        for (int row = topRow; row <= bottomRow; row++) {
            moveTo(out, row, textStartCol);
            if (shift >= width) {
                for (int c = textStartCol; c <= textEndCol; c++) {
                    Cell cell = current.get(row, c);
                    if (cell.continuation()) {
                        continue;
                    }
                    String styleAnsi = ansiFor(cell.style(), colorEnabled);
                    if (!styleAnsi.equals(activeStyleAnsi)) {
                        out.append(styleAnsi);
                        activeStyleAnsi = styleAnsi;
                    }
                    out.append(cell.grapheme());
                }
                continue;
            }
            if (deltaColumns > 0) {
                out.append("\u001B[").append(shift).append('P');
                int enteringStart = textEndCol - shift + 1;
                moveTo(out, row, enteringStart);
                for (int c = enteringStart; c <= textEndCol; c++) {
                    Cell cell = current.get(row, c);
                    if (cell.continuation()) {
                        continue;
                    }
                    String styleAnsi = ansiFor(cell.style(), colorEnabled);
                    if (!styleAnsi.equals(activeStyleAnsi)) {
                        out.append(styleAnsi);
                        activeStyleAnsi = styleAnsi;
                    }
                    out.append(cell.grapheme());
                }
            } else {
                out.append("\u001B[").append(shift).append('@');
                moveTo(out, row, textStartCol);
                int enteringEnd = textStartCol + shift - 1;
                for (int c = textStartCol; c <= enteringEnd; c++) {
                    Cell cell = current.get(row, c);
                    if (cell.continuation()) {
                        continue;
                    }
                    String styleAnsi = ansiFor(cell.style(), colorEnabled);
                    if (!styleAnsi.equals(activeStyleAnsi)) {
                        out.append(styleAnsi);
                        activeStyleAnsi = styleAnsi;
                    }
                    out.append(cell.grapheme());
                }
            }
        }
        return activeStyleAnsi;
    }

    private static boolean horizontalOverlapMatches(
        CellBuffer previous,
        CellBuffer current,
        int topRow,
        int bottomRow,
        int textStartCol,
        int textEndCol,
        int deltaColumns
    ) {
        int width = textEndCol - textStartCol + 1;
        int shift = (int) Math.min(Math.abs((long) deltaColumns), (long) width);
        if (shift >= width) {
            return true;
        }
        for (int row = topRow; row <= bottomRow; row++) {
            if (deltaColumns > 0) {
                for (int col = textStartCol; col <= textEndCol - shift; col++) {
                    if (!current.get(row, col).equals(previous.get(row, col + shift))) {
                        return false;
                    }
                }
            } else {
                for (int col = textStartCol + shift; col <= textEndCol; col++) {
                    if (!current.get(row, col).equals(previous.get(row, col - shift))) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    private static VerticalScroll detectVerticalScroll(
        CellBuffer previous,
        CellBuffer current,
        int scrollRegionTop,
        int scrollRegionHeight,
        int textStartCol,
        int textEndCol
    ) {
        if (scrollRegionTop < 0 || scrollRegionHeight <= 1) {
            return null;
        }
        if (previous.rows() != current.rows() || previous.cols() != current.cols()) {
            return null;
        }
        int top = Math.max(0, scrollRegionTop);
        int bottom = Math.min(current.rows() - 1, top + scrollRegionHeight - 1);
        int height = bottom - top + 1;
        if (height <= 1) {
            return null;
        }
        long[][] prevTextSig = buildTextRegionSignatures(previous, top, bottom, textStartCol, textEndCol);
        long[][] curTextSig = buildTextRegionSignatures(current, top, bottom, textStartCol, textEndCol);

        VerticalScroll best = null;
        double bestCoverage = 0.0;
        for (int delta = -(height - 1); delta <= height - 1; delta++) {
            if (delta == 0) {
                continue;
            }
            int overlap = height - Math.abs(delta);
            if (overlap <= 1) {
                continue;
            }
            int curStart = delta > 0 ? top : top - delta;
            int prevStart = curStart + delta;
            boolean[] matchedRows = new boolean[current.rows()];
            int matched = 0;
            for (int i = 0; i < overlap; i++) {
                int curRow = curStart + i;
                int prevRow = prevStart + i;
                if (rowsEqual(prevTextSig, curTextSig, prevRow, curRow)) {
                    matchedRows[curRow] = true;
                    matched++;
                }
            }
            double coverage = (double) matched / (double) overlap;
            if (coverage < 0.75) {
                continue;
            }
            VerticalScroll candidate = new VerticalScroll(top, bottom, delta, matchedRows);
            if (best == null
                || coverage > bestCoverage
                || (coverage == bestCoverage && candidate.overlap() > best.overlap())
                || (coverage == bestCoverage
                    && candidate.overlap() == best.overlap()
                    && Math.abs(candidate.delta()) < Math.abs(best.delta()))) {
                best = candidate;
                bestCoverage = coverage;
            }
        }
        return best;
    }

    private static boolean rowsEqual(long[][] prevTextSig, long[][] curTextSig, int prevRow, int curRow) {
        return prevTextSig[0][prevRow] == curTextSig[0][curRow]
            && prevTextSig[1][prevRow] == curTextSig[1][curRow];
    }

    private static long[][] buildTextRegionSignatures(
        CellBuffer buffer,
        int top,
        int bottom,
        int textStartCol,
        int textEndCol
    ) {
        long[] sig1 = new long[buffer.rows()];
        long[] sig2 = new long[buffer.rows()];
        for (int r = top; r <= bottom; r++) {
            long h1 = 0L;
            long h2 = 0L;
            for (int c = textStartCol; c <= textEndCol; c++) {
                Cell cell = buffer.get(r, c);
                h1 ^= mix(cell.hashCode(), c, 0x9E3779B97F4A7C15L);
                h2 ^= mix(cell.hashCode(), c, 0xD1B54A32D192ED03L);
            }
            sig1[r] = h1;
            sig2[r] = h2;
        }
        return new long[][] {sig1, sig2};
    }

    private static long mix(int cellHash, int col, long seed) {
        long x = seed ^ ((long) cellHash * 0x9E3779B185EBCA87L) ^ (long) col * 0xC2B2AE3D27D4EB4FL;
        x ^= (x >>> 33);
        x *= 0xFF51AFD7ED558CCDL;
        x ^= (x >>> 33);
        x *= 0xC4CEB9FE1A85EC53L;
        x ^= (x >>> 33);
        return x;
    }

    private static void applyVerticalScroll(StringBuilder out, VerticalScroll scroll) {
        out.append("\u001B[")
            .append(scroll.top() + 1)
            .append(';')
            .append(scroll.bottom() + 1)
            .append('r');
        out.append("\u001B[").append(scroll.top() + 1).append(";1H");
        if (scroll.delta() > 0) {
            out.append("\u001B[").append(scroll.delta()).append('S');
        } else {
            out.append("\u001B[").append(-scroll.delta()).append('T');
        }
        out.append("\u001B[r");
    }

    private String drawEnteringRows(
        StringBuilder out,
        CellBuffer current,
        boolean colorEnabled,
        String activeStyleAnsi,
        VerticalScroll scroll
    ) {
        int start = scroll.enteringStart();
        int count = scroll.enteringCount();
        for (int i = 0; i < count; i++) {
            int row = start + i;
            moveTo(out, row, 0);
            for (int c = 0; c < current.cols(); c++) {
                Cell cell = current.get(row, c);
                if (cell.continuation()) {
                    continue;
                }
                String styleAnsi = ansiFor(cell.style(), colorEnabled);
                if (!styleAnsi.equals(activeStyleAnsi)) {
                    out.append(styleAnsi);
                    activeStyleAnsi = styleAnsi;
                }
                out.append(cell.grapheme());
            }
        }
        return activeStyleAnsi;
    }

    private String ansiFor(CellStyle style, boolean colorEnabled) {
        Map<CellStyle, String> cache = colorEnabled ? colorStyleCache : monoStyleCache;
        String cached = cache.get(style);
        if (cached != null) {
            return cached;
        }
        String ansi = colorEnabled ? toAnsi(style) : toMonochromeAnsi(style);
        cache.put(style, ansi);
        return ansi;
    }

    private record VerticalScroll(int top, int bottom, int delta, boolean[] matchedRows) {
        private int overlap() {
            return (bottom - top + 1) - Math.abs(delta);
        }

        private int enteringCount() {
            return Math.abs(delta);
        }

        private int enteringStart() {
            if (delta > 0) {
                return bottom - delta + 1;
            }
            return top;
        }

        private boolean isEnteringRow(int row) {
            if (delta > 0) {
                return row >= enteringStart() && row <= bottom;
            }
            return row >= top && row < top + enteringCount();
        }

        private boolean skipRow(int row) {
            if (row < top || row > bottom) {
                return false;
            }
            return isEnteringRow(row);
        }

        private boolean inRegion(int row) {
            return row >= top && row <= bottom;
        }
    }

    private static int firstDifferentColumn(CellBuffer previous, CellBuffer current, int row, int fromCol, int toCol) {
        return firstDifferentColumn(previous, current, row, row, fromCol, toCol);
    }

    private static int firstDifferentColumn(
        CellBuffer previous,
        CellBuffer current,
        int previousRow,
        int currentRow,
        int fromCol,
        int toCol
    ) {
        if (fromCol > toCol) {
            return -1;
        }
        for (int c = fromCol; c <= toCol; c++) {
            if (!current.get(currentRow, c).equals(previous.get(previousRow, c))) {
                return c;
            }
        }
        return -1;
    }

    private static int lastDifferentColumn(
        CellBuffer previous,
        CellBuffer current,
        int row,
        int firstDiff,
        int toCol
    ) {
        return lastDifferentColumn(previous, current, row, row, firstDiff, toCol);
    }

    private static int lastDifferentColumn(
        CellBuffer previous,
        CellBuffer current,
        int previousRow,
        int currentRow,
        int firstDiff,
        int toCol
    ) {
        for (int c = toCol; c >= firstDiff; c--) {
            if (!current.get(currentRow, c).equals(previous.get(previousRow, c))) {
                return c;
            }
        }
        return firstDiff;
    }

    private RowWrite diffRowRange(
        CellBuffer previous,
        CellBuffer current,
        boolean colorEnabled,
        int previousRow,
        int currentRow,
        int fromCol,
        int toCol,
        StringBuilder out,
        String activeStyleAnsi
    ) {
        if (previous.rowHasWideCells(previousRow) || current.rowHasWideCells(currentRow)) {
            String resultingStyle = drawRowRange(
                current,
                colorEnabled,
                currentRow,
                0,
                current.cols() - 1,
                out,
                activeStyleAnsi
            );
            return new RowWrite(true, resultingStyle);
        }
        int firstDiff = firstDifferentColumn(previous, current, previousRow, currentRow, fromCol, toCol);
        if (firstDiff < 0) {
            return new RowWrite(false, activeStyleAnsi);
        }
        int lastDiff = lastDifferentColumn(previous, current, previousRow, currentRow, firstDiff, toCol);
        int c = firstDiff;
        boolean wroteAny = false;
        while (c <= lastDiff) {
            while (c <= lastDiff && current.get(currentRow, c).equals(previous.get(previousRow, c))) {
                c++;
            }
            if (c > lastDiff) {
                break;
            }
            moveTo(out, currentRow, c);
            while (c <= lastDiff) {
                Cell next = current.get(currentRow, c);
                Cell prev = previous.get(previousRow, c);
                if (next.equals(prev)) {
                    break;
                }
                String styleAnsi = ansiFor(next.style(), colorEnabled);
                if (!styleAnsi.equals(activeStyleAnsi)) {
                    out.append(styleAnsi);
                    activeStyleAnsi = styleAnsi;
                }
                if (!next.continuation()) {
                    out.append(next.grapheme());
                }
                wroteAny = true;
                c++;
            }
        }
        return new RowWrite(wroteAny, activeStyleAnsi);
    }

    private String drawRowRange(
        CellBuffer current,
        boolean colorEnabled,
        int row,
        int fromCol,
        int toCol,
        StringBuilder out,
        String activeStyleAnsi
    ) {
        if (fromCol > toCol) {
            return activeStyleAnsi;
        }
        moveTo(out, row, fromCol);
        for (int c = fromCol; c <= toCol; c++) {
            Cell cell = current.get(row, c);
            if (cell.continuation()) {
                continue;
            }
            String styleAnsi = ansiFor(cell.style(), colorEnabled);
            if (!styleAnsi.equals(activeStyleAnsi)) {
                out.append(styleAnsi);
                activeStyleAnsi = styleAnsi;
            }
            out.append(cell.grapheme());
        }
        return activeStyleAnsi;
    }

    private record RowWrite(boolean wroteAny, String activeStyleAnsi) {
    }

    private static void moveTo(StringBuilder out, int row, int col) {
        out.append("\u001B[").append(row + 1).append(';').append(col + 1).append('H');
    }

    private static String toAnsi(CellStyle style) {
        return "\u001B["
            + fgCode(style.fg()) + ';'
            + bgCode(style.bg()) + ';'
            + (style.bold() ? "1" : "22") + ';'
            + (style.underline() ? "4" : "24") + 'm';
    }

    private static String toMonochromeAnsi(CellStyle style) {
        return "\u001B["
            + "39;49;"
            + (style.bold() ? "1" : "22") + ';'
            + (style.underline() ? "4" : "24") + ';'
            + (style.bg() != CellColor.DEFAULT ? "7" : "27") + 'm';
    }

    private static int fgCode(CellColor color) {
        return switch (color) {
            case DEFAULT -> 39;
            case BLACK -> 30;
            case RED -> 31;
            case GREEN -> 32;
            case YELLOW -> 33;
            case BLUE -> 34;
            case MAGENTA -> 35;
            case CYAN -> 36;
            case WHITE -> 37;
            case BRIGHT_BLACK -> 90;
            case BRIGHT_RED -> 91;
            case BRIGHT_GREEN -> 92;
            case BRIGHT_YELLOW -> 93;
            case BRIGHT_BLUE -> 94;
            case BRIGHT_MAGENTA -> 95;
            case BRIGHT_CYAN -> 96;
            case BRIGHT_WHITE -> 97;
        };
    }

    private static int bgCode(CellColor color) {
        return switch (color) {
            case DEFAULT -> 49;
            case BLACK -> 40;
            case RED -> 41;
            case GREEN -> 42;
            case YELLOW -> 43;
            case BLUE -> 44;
            case MAGENTA -> 45;
            case CYAN -> 46;
            case WHITE -> 47;
            case BRIGHT_BLACK -> 100;
            case BRIGHT_RED -> 101;
            case BRIGHT_GREEN -> 102;
            case BRIGHT_YELLOW -> 103;
            case BRIGHT_BLUE -> 104;
            case BRIGHT_MAGENTA -> 105;
            case BRIGHT_CYAN -> 106;
            case BRIGHT_WHITE -> 107;
        };
    }
}
