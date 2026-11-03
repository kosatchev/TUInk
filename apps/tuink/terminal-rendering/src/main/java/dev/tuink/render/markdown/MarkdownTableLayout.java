package dev.tuink.render.markdown;

import dev.tuiframe.cell.CellText;
import dev.tuiframe.text.TerminalText;
import dev.tuink.editor.document.DocumentId;
import dev.tuink.editor.document.TextRange;
import dev.tuink.markdown.MarkdownBlock;
import dev.tuink.markdown.MarkdownDocument;
import dev.tuink.markdown.MarkdownInline;
import java.util.ArrayList;
import java.util.List;

/** Framed terminal tables. Cell layout uses the same inline/math baseline rules as paragraphs. */
public final class MarkdownTableLayout {
    private static final int MAX_COLUMNS = 64;
    private static final int MAX_ROWS = 20_000;
    private static final int MAX_EXTENT = 4096;
    private static final DocumentId CELL_DOCUMENT = new DocumentId("markdown-table-cell");
    private MarkdownTableLayout() {}

    public static List<MarkdownLayout.Line> layout(MarkdownBlock.Table table, int width, String prefix,
                                                  MarkdownLayout.EmbeddedRenderer embedded) {
        int columns = table.rows().stream().mapToInt(row -> row.cells().size()).max().orElse(0);
        if (columns == 0) return List.of();
        if (columns > MAX_COLUMNS) return failure("Table exceeds the column budget", table.source());
        int[] natural = new int[columns], minimum = new int[columns];
        for (MarkdownBlock.Row row : table.rows()) {
            check();
            for (int column = 0; column < row.cells().size(); column++) {
                MarkdownBlock.Cell cell = row.cells().get(column);
                List<MarkdownLayout.Line> measured = cell(cell, 1024, embedded);
                natural[column] = Math.max(natural[column], measured.stream().mapToInt(line -> CellText.width(line.text())).max().orElse(1));
                minimum[column] = Math.max(minimum[column], minimum(cell.content(), embedded));
            }
        }
        int available = Math.max(0, width - CellText.width(prefix) - 3 * columns - 1);
        int totalMinimum = 0, totalNatural = 0;
        int[] sizes = new int[columns];
        for (int column = 0; column < columns; column++) {
            natural[column] = Math.max(1, natural[column]);
            minimum[column] = Math.max(minimum[column], Math.min(3, natural[column]));
            natural[column] = Math.max(natural[column], minimum[column]);
            sizes[column] = minimum[column]; totalMinimum += sizes[column]; totalNatural += natural[column];
        }
        if ((long)totalMinimum + 3L * columns + 1 + CellText.width(prefix) > MAX_EXTENT)
            return failure("Table exceeds the layout width budget", table.source());
        int target = Math.min(totalNatural, Math.max(available, totalMinimum));
        for (int used = totalMinimum; used < target;) {
            for (int column = 0; column < columns && used < target; column++) {
                if (sizes[column] < natural[column]) { sizes[column]++; used++; }
            }
        }
        List<MarkdownLayout.Line> output = new ArrayList<>();
        border(output, prefix, sizes, '┌', '┬', '┐', table.source());
        for (int rowIndex = 0; rowIndex < table.rows().size(); rowIndex++) {
            check();
            MarkdownBlock.Row row = table.rows().get(rowIndex);
            List<List<MarkdownLayout.Line>> cells = new ArrayList<>();
            int height = 1;
            for (int column = 0; column < columns; column++) {
                List<MarkdownLayout.Line> cell = column < row.cells().size()
                    ? cell(row.cells().get(column), sizes[column], embedded)
                    : List.of(empty(row.source()));
                cells.add(cell); height = Math.max(height, cell.size());
            }
            if ((long)output.size() + height + 2 > MAX_ROWS) return failure("Table exceeds the layout row budget", table.source());
            for (int y = 0; y < height; y++) {
                StringBuilder text = new StringBuilder(prefix).append('│');
                StringBuilder copy = new StringBuilder();
                List<MarkdownLayout.Run> runs = new ArrayList<>();
                runs.add(new MarkdownLayout.Run(0, text.length(), MarkdownLayout.Role.TABLE, null));
                for (int column = 0; column < columns; column++) {
                    MarkdownLayout.Line line = y < cells.get(column).size() ? cells.get(column).get(y) : empty(row.source());
                    int padding = Math.max(0, sizes[column] - CellText.width(line.text()));
                    MarkdownBlock.Alignment alignment = column < table.alignments().size() ? table.alignments().get(column) : MarkdownBlock.Alignment.LEFT;
                    int left = alignment == MarkdownBlock.Alignment.RIGHT ? padding : alignment == MarkdownBlock.Alignment.CENTER ? padding / 2 : 0;
                    int frameStart = text.length();
                    text.append(' ').append(" ".repeat(left));
                    runs.add(new MarkdownLayout.Run(frameStart, text.length(), MarkdownLayout.Role.TABLE, null));
                    int start = text.length(); text.append(line.text());
                    if (line.runs().isEmpty() && !line.text().isEmpty())
                        runs.add(new MarkdownLayout.Run(start, text.length(), row.header() ? MarkdownLayout.Role.STRONG : MarkdownLayout.Role.TEXT, line.source()));
                    for (MarkdownLayout.Run run : line.runs()) {
                        int end = Math.min(line.text().length(), run.end());
                        if (run.start() < end) runs.add(new MarkdownLayout.Run(start + run.start(), start + end,
                            row.header() && run.role() == MarkdownLayout.Role.TEXT ? MarkdownLayout.Role.STRONG : run.role(), run.source()));
                    }
                    frameStart = text.length();
                    text.append(" ".repeat(padding - left)).append(" │");
                    runs.add(new MarkdownLayout.Run(frameStart, text.length(), MarkdownLayout.Role.TABLE, null));
                    if (column > 0) copy.append('\t');
                    copy.append(line.copyText());
                }
                output.add(new MarkdownLayout.Line(text.toString(), runs, copy.toString(), row.source(), "table"));
            }
            if (rowIndex + 1 < table.rows().size()) border(output, prefix, sizes, '├', '┼', '┤', row.source());
        }
        border(output, prefix, sizes, '└', '┴', '┘', table.source());
        return List.copyOf(output);
    }

    private static List<MarkdownLayout.Line> cell(MarkdownBlock.Cell cell, int width, MarkdownLayout.EmbeddedRenderer embedded) {
        MarkdownDocument document = new MarkdownDocument(CELL_DOCUMENT, 0,
            List.of(new MarkdownBlock.Paragraph(cell.content(), cell.source())), List.of());
        List<MarkdownLayout.Line> rows = new ArrayList<>(new MarkdownLayout(Math.max(1, width), embedded).layout(document).lines());
        while (!rows.isEmpty() && rows.get(rows.size() - 1).kind().equals("space")) rows.remove(rows.size() - 1);
        return rows.isEmpty() ? List.of(empty(cell.source())) : rows.stream().map(MarkdownTableLayout::expandTabs).toList();
    }

    private static MarkdownLayout.Line expandTabs(MarkdownLayout.Line line) {
        if (line.text().indexOf('\t') < 0) return line;
        StringBuilder text = new StringBuilder();
        int[] offsets = new int[line.text().length() + 1];
        int visual = 0;
        TerminalText.GraphemeCursor cursor = TerminalText.graphemeCursor(line.text());
        while (cursor.advance()) {
            for (int offset = cursor.start(); offset < cursor.end(); offset++) offsets[offset] = text.length();
            int cells = cursor.isTab() ? 4 - visual % 4 : Math.max(1, cursor.width());
            if (cursor.isTab()) text.append(" ".repeat(cells));
            else text.append(line.text(), cursor.start(), cursor.end());
            visual += cells; offsets[cursor.end()] = text.length();
        }
        List<MarkdownLayout.Run> runs = new ArrayList<>();
        for (MarkdownLayout.Run run : line.runs()) {
            int start = Math.min(line.text().length(), run.start()), end = Math.min(line.text().length(), run.end());
            if (start < end) runs.add(new MarkdownLayout.Run(offsets[start], offsets[end], run.role(), run.source()));
        }
        return new MarkdownLayout.Line(text.toString(), runs, line.copyText(), line.source(), line.kind());
    }

    /** Text can wrap; code and math are indivisible boxes and reserve their whole width. */
    private static int minimum(List<MarkdownInline> content, MarkdownLayout.EmbeddedRenderer embedded) {
        int width = 1;
        for (MarkdownInline inline : content) {
            check();
            if (inline instanceof MarkdownInline.Math n)
                width = Math.max(width, embedded.math(n.expression()).lines().stream().mapToInt(CellText::width).max().orElse(1));
            else if (inline instanceof MarkdownInline.Code n) width = Math.max(width, CellText.width(n.text()));
            else if (inline instanceof MarkdownInline.Styled n) width = Math.max(width, minimum(n.content(), embedded));
            else if (inline instanceof MarkdownInline.Link n) width = Math.max(width, minimum(n.content(), embedded));
            else if (inline instanceof MarkdownInline.Text n) {
                TerminalText.GraphemeCursor cursor = TerminalText.graphemeCursor(n.text());
                while (cursor.advance()) width = Math.max(width, cursor.width());
            }
        }
        return width;
    }

    private static void border(List<MarkdownLayout.Line> rows, String prefix, int[] sizes,
                               char left, char middle, char right, TextRange source) {
        StringBuilder text = new StringBuilder(prefix).append(left);
        for (int column = 0; column < sizes.length; column++) {
            if (column > 0) text.append(middle);
            text.append("─".repeat(sizes[column] + 2));
        }
        text.append(right);
        rows.add(new MarkdownLayout.Line(text.toString(), List.of(new MarkdownLayout.Run(0, text.length(), MarkdownLayout.Role.TABLE, null)), "", source, "table-border"));
    }
    private static MarkdownLayout.Line empty(TextRange source) { return new MarkdownLayout.Line("", List.of(), "", source, "table"); }
    private static List<MarkdownLayout.Line> failure(String message, TextRange source) {
        String text = "[!] " + message + "; use the source view for this table";
        return List.of(new MarkdownLayout.Line(text, List.of(new MarkdownLayout.Run(0, text.length(), MarkdownLayout.Role.DIAGNOSTIC, source)), "", source, "diagnostic"));
    }
    private static void check() {
        if (Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException("Table layout cancelled");
    }
}
