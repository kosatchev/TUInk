package dev.tuink.markdown;

import java.util.List;

/** Semantic clipboard formats; generated terminal borders and layout whitespace are excluded. */
public final class MarkdownCopy {
    private MarkdownCopy() {}

    /** Tab-separated records with quoted fields for embedded delimiters, newlines and quotes. */
    public static String tableTsv(MarkdownBlock.Table table) {
        StringBuilder output = new StringBuilder();
        for (int row = 0; row < table.rows().size(); row++) {
            if (row > 0) output.append('\n');
            List<MarkdownBlock.Cell> cells = table.rows().get(row).cells();
            for (int column = 0; column < cells.size(); column++) {
                if (column > 0) output.append('\t');
                MarkdownBlock.Cell cell = cells.get(column);
                String value = TextProjection.from(List.of(new MarkdownBlock.Paragraph(cell.content(), cell.source()))).text();
                if (value.indexOf('\t') >= 0 || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0 || value.indexOf('"') >= 0)
                    output.append('"').append(value.replace("\"", "\"\"")).append('"');
                else output.append(value);
            }
        }
        return output.toString();
    }
}
