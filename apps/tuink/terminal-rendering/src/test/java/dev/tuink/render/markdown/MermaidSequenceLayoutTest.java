package dev.tuink.render.markdown;

import static org.junit.jupiter.api.Assertions.*;

import dev.tuiframe.cell.CellText;
import dev.tuiframe.text.TerminalText;
import dev.tuink.markdown.mermaid.Diagram;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class MermaidSequenceLayoutTest {
    @Test void unequalLabelsShareHeaderAndFooterAttachmentRowsWithoutLifelineGaps() {
        var sequence = new Diagram.Sequence("", List.of(
            participant("A", "Short"), participant("B", "Tall\nHeader")), List.of());
        var lines = render(sequence);
        int headerPortRow = rowContaining(lines, "┬");
        int footerPortRow = rowContaining(lines, "┴");
        var ports = columnsOf(lines.get(headerPortRow), "┬");

        assertEquals(3, headerPortRow, "Both headers include the second label row");
        assertEquals(2, ports.size());
        assertEquals(ports, columnsOf(lines.get(footerPortRow), "┴"));
        assertEquals(4, lines.size() - footerPortRow, "Footer boxes have the same common height");
        for (int column : ports) {
            assertEquals("─", cellAt(lines.get(0), column));
            assertEquals("─", cellAt(lines.get(lines.size() - 1), column));
            for (int row = headerPortRow + 1; row < footerPortRow; row++)
                assertEquals("│", cellAt(lines.get(row), column),
                    "Lifeline must touch both boxes without a blank row: " + row + ", " + column);
        }
        String text = String.join("\n", lines);
        assertEquals(2, occurrences(text, "Short"));
        assertEquals(2, occurrences(text, "Tall"));
        assertEquals(2, occurrences(text, "Header"));
    }

    @Test void multilineHeadersStayConnectedBeforeAndAfterActualMessages() {
        var sequence = new Diagram.Sequence("", List.of(
            participant("A", "Short"), participant("B", "Tall\nHeader")),
            List.of(new Diagram.Message("A", "B", "ping", false), new Diagram.Message("B", "A", "pong", true)));
        var lines = render(sequence);
        int header = rowContaining(lines, "┬"), footer = rowContaining(lines, "┴");
        var ports = columnsOf(lines.get(header), "┬");
        for (int column : ports) {
            assertEquals("│", cellAt(lines.get(header + 1), column));
            assertEquals("│", cellAt(lines.get(footer - 1), column));
        }
        assertTrue(rowContaining(lines, "ping") < rowContaining(lines, "pong"));
        assertArrow(lines, rowContaining(lines, "ping") + 1, ports.get(1) - 1, "→");
        assertArrow(lines, rowContaining(lines, "pong") + 1, ports.get(0) + 1, "←");
    }

    @Test void nonAdjacentMessagesKeepTheirRealReceiverAndRepliesAndSelfCallsKeepDirection() {
        var sequence = new Diagram.Sequence("", List.of(participant("A", "A"), participant("B", "B"), participant("C", "C")),
            List.of(new Diagram.Message("A", "C", "forward-last", false),
                new Diagram.Message("C", "B", "reply-mid", true),
                new Diagram.Message("B", "B", "self-mid", false),
                new Diagram.Message("C", "A", "back-first", true)));
        var lines = render(sequence);
        var ports = columnsOf(lines.get(rowContaining(lines, "┬")), "┬");
        int forward = rowContaining(lines, "forward-last") + 1;
        assertArrow(lines, forward, ports.get(2) - 1, "→");
        assertEquals("│", cellAt(lines.get(forward), ports.get(2)));
        assertNotEquals("→", cellAt(lines.get(forward), ports.get(1) - 1), "Intermediate participant is not the receiver");
        assertEquals(1, occurrences(lines.get(forward), "→"));
        int reply = rowContaining(lines, "reply-mid") + 1;
        assertArrow(lines, reply, ports.get(1) + 1, "←");
        assertTrue(lines.get(reply).contains("╌"));
        int self = rowContaining(lines, "self-mid") + 1;
        assertArrow(lines, self + 2, ports.get(1) + 1, "←");
        assertTrue(lines.get(self).contains("┐"));
        assertTrue(lines.get(self + 2).contains("┘"));
        assertArrow(lines, rowContaining(lines, "back-first") + 1, ports.get(0) + 1, "←");
        assertTrue(rowContaining(lines, "forward-last") < rowContaining(lines, "reply-mid"));
        assertTrue(rowContaining(lines, "reply-mid") < rowContaining(lines, "self-mid"));
        assertTrue(rowContaining(lines, "self-mid") < rowContaining(lines, "back-first"));
    }

    @Test void actorAndWideUnicodeLabelsPreserveCellAlignedPortsAndMessageText() {
        var sequence = new Diagram.Sequence("", List.of(
            new Diagram.Participant("A", "界🚀", true), participant("B", "e\u0301\n次")), List.of(
            new Diagram.Message("A", "B", "計算 e\u0301😀", false),
            new Diagram.Message("B", "A", "戻る\n第二行", true)));
        var lines = render(sequence);
        String text = String.join("\n", lines);
        assertEquals(2, occurrences(text, "○ 界🚀"));
        assertEquals(2, occurrences(text, "次"));
        assertTrue(text.contains("計算 e\u0301😀"));
        var ports = columnsOf(lines.get(rowContaining(lines, "┬")), "┬");
        assertEquals(2, ports.size());
        assertArrow(lines, rowContaining(lines, "計算 e\u0301😀") + 1, ports.get(1) - 1, "→");
        assertArrow(lines, rowContaining(lines, "第二行") + 1, ports.get(0) + 1, "←");
        assertEquals(ports, columnsOf(lines.get(rowContaining(lines, "┴")), "┴"));
        assertTrue(lines.stream().allMatch(line -> CellText.width(line) <= 1024));
    }

    @Test void invalidAndOversizedModelsKeepTheirLocalFallbackAndCancellationKeepsTheInterrupt() {
        var empty = new Diagram.Sequence("source", List.of(), List.of());
        assertTrue(MermaidLayout.render(empty, 80).get(0).contains("no participants"));
        var unknown = new Diagram.Sequence("source", List.of(participant("A", "A")),
            List.of(new Diagram.Message("A", "missing", "text", false)));
        assertTrue(MermaidLayout.render(unknown, 80).get(0).contains("unknown participant"));
        var duplicate = new Diagram.Sequence("source", List.of(participant("A", "first"), participant("A", "second")), List.of());
        assertTrue(MermaidLayout.render(duplicate, 80).get(0).contains("Duplicate"));
        var many = new ArrayList<Diagram.Participant>();
        for (int index = 0; index < 128; index++) many.add(participant("p" + index, "Participant " + index));
        var oversized = new Diagram.Sequence("source", many, List.of());
        assertTrue(MermaidLayout.render(oversized, 80).get(0).contains("budget"));
        Thread.currentThread().interrupt();
        try {
            assertTrue(MermaidLayout.render(new Diagram.Sequence("source", List.of(participant("A", "A")), List.of()), 80)
                .get(0).contains("cancelled"));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally { Thread.interrupted(); }
    }

    private static Diagram.Participant participant(String id, String label) { return new Diagram.Participant(id, label, false); }

    private static List<String> render(Diagram.Sequence sequence) {
        var lines = MermaidLayout.render(sequence, 80);
        assertFalse(String.join("\n", lines).contains("[Mermaid:"), String.join("\n", lines));
        return lines;
    }

    private static void assertArrow(List<String> lines, int row, int column, String expected) {
        assertEquals(expected, cellAt(lines.get(row), column), "Arrow at terminal cell " + column + ", " + row);
    }

    private static int rowContaining(List<String> lines, String text) {
        for (int row = 0; row < lines.size(); row++) if (lines.get(row).contains(text)) return row;
        fail("Missing " + text + " in " + String.join("\n", lines));
        return -1;
    }

    private static List<Integer> columnsOf(String line, String glyph) {
        var result = new ArrayList<Integer>();
        var cursor = TerminalText.graphemeCursor(line);
        int column = 0;
        while (cursor.advance()) {
            if (line.substring(cursor.start(), cursor.end()).equals(glyph)) result.add(column);
            column += Math.max(1, cursor.width());
        }
        return result;
    }

    private static String cellAt(String line, int target) {
        var cursor = TerminalText.graphemeCursor(line);
        int column = 0;
        while (cursor.advance()) {
            int width = Math.max(1, cursor.width());
            if (column == target) return line.substring(cursor.start(), cursor.end());
            if (column < target && target < column + width) return "";
            column += width;
        }
        return " ";
    }

    private static int occurrences(String text, String token) {
        int count = 0;
        for (int index = 0; (index = text.indexOf(token, index)) >= 0; index += token.length()) count++;
        return count;
    }
}
