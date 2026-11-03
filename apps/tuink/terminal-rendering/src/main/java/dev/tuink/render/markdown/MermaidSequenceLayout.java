package dev.tuink.render.markdown;

import static dev.tuink.render.markdown.MermaidLayout.drawBox;
import static dev.tuink.render.markdown.MermaidLayout.wrap;

import dev.tuiframe.cell.CellText;
import dev.tuink.markdown.mermaid.Diagram;
import dev.tuink.render.markdown.MermaidLayout.Grid;
import dev.tuink.render.markdown.MermaidLayout.LayoutLimit;
import dev.tuink.render.markdown.MermaidLayout.Point;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Bounded sequence layout over the portable model, using the shared terminal grid. */
final class MermaidSequenceLayout {
    private MermaidSequenceLayout() {}

    static List<String> render(Diagram.Sequence diagram, int labelWidth) {
        checkCancellation();
        if (diagram.participants().isEmpty()) throw new LayoutLimit("Sequence diagram contains no participants");
        if (diagram.participants().size() > 1_024 || diagram.messages().size() > 4_096) {
            throw new LayoutLimit("Mermaid sequence budget exceeded");
        }
        Map<String, Integer> indexes = new LinkedHashMap<>();
        List<List<String>> participantLabels = new ArrayList<>();
        int maxParticipantWidth = 1;
        int headerHeight = 3;
        for (Diagram.Participant participant : diagram.participants()) {
            checkCancellation();
            if (indexes.put(participant.id(), indexes.size()) != null) throw new LayoutLimit("Duplicate participant identity");
            List<String> label = wrap((participant.actor() ? "○ " : "") + participant.label(), labelWidth);
            participantLabels.add(label);
            maxParticipantWidth = Math.max(maxParticipantWidth, label.stream().mapToInt(CellText::width).max().orElse(1) + 4);
            headerHeight = Math.max(headerHeight, label.size() + 2);
        }
        List<List<String>> messageLabels = new ArrayList<>();
        int maxMessageWidth = 1;
        int bodyHeight = 0;
        for (Diagram.Message message : diagram.messages()) {
            checkCancellation();
            if (!indexes.containsKey(message.from()) || !indexes.containsKey(message.to())) {
                throw new LayoutLimit("Message refers to an unknown participant");
            }
            List<String> label = wrap(message.label(), labelWidth);
            messageLabels.add(label);
            maxMessageWidth = Math.max(maxMessageWidth, label.stream().mapToInt(CellText::width).max().orElse(1));
            bodyHeight += label.size() + (message.from().equals(message.to()) ? 4 : 2);
        }
        int spacing = Math.max(maxParticipantWidth + 4, maxMessageWidth + 6);
        int margin = maxParticipantWidth / 2 + 2;
        int totalWidth = 2 * margin + (diagram.participants().size() - 1) * spacing + maxMessageWidth + 6;
        int totalHeight = headerHeight * 2 + bodyHeight + 2;
        Grid grid = new Grid(totalWidth, totalHeight);
        int[] centers = new int[diagram.participants().size()];
        for (int index = 0; index < centers.length; index++) {
            checkCancellation();
            centers[index] = margin + index * spacing;
            grid.path(List.of(new Point(centers[index], headerHeight), new Point(centers[index], totalHeight - headerHeight - 1)), false);
        }
        int y = headerHeight + 1;
        for (int index = 0; index < diagram.messages().size(); index++) {
            checkCancellation();
            Diagram.Message message = diagram.messages().get(index);
            List<String> label = messageLabels.get(index);
            int from = centers[indexes.get(message.from())];
            int to = centers[indexes.get(message.to())];
            int labelW = label.stream().mapToInt(CellText::width).max().orElse(1);
            int labelX = from == to ? from + 2 : Math.min(from, to) + Math.max(1, (Math.abs(to - from) - labelW) / 2);
            for (int row = 0; row < label.size(); row++) grid.text(labelX, y + row, label.get(row));
            y += label.size();
            if (from == to) {
                int right = from + Math.max(4, labelW + 2);
                grid.path(List.of(new Point(from, y), new Point(right, y), new Point(right, y + 2), new Point(from + 1, y + 2)), message.dashed());
                grid.put(from + 1, y + 2, "←");
                y += 4;
            } else {
                int tip = to > from ? to - 1 : to + 1;
                grid.path(List.of(new Point(from, y), new Point(tip, y)), message.dashed());
                grid.put(tip, y, to > from ? "→" : "←");
                y += 2;
            }
        }
        for (int index = 0; index < centers.length; index++) {
            checkCancellation();
            // Every participant ends at the same attachment row, including
            // short and explicitly multiline labels. Reuse the padded label
            // for both boxes so the footer has the same height and alignment.
            List<String> label = padded(participantLabels.get(index), headerHeight - 2);
            int boxWidth = label.stream().mapToInt(CellText::width).max().orElse(1) + 4;
            drawBox(grid, centers[index] - boxWidth / 2, 0, boxWidth, label, false);
            drawBox(grid, centers[index] - boxWidth / 2, totalHeight - headerHeight, boxWidth, label, false);
            grid.put(centers[index], headerHeight - 1, "┬");
            grid.put(centers[index], totalHeight - headerHeight, "┴");
        }
        return grid.lines();
    }

    private static List<String> padded(List<String> label, int height) {
        if (label.size() == height) return label;
        var padded = new ArrayList<String>(height);
        padded.addAll(label);
        while (padded.size() < height) padded.add("");
        return padded;
    }

    private static void checkCancellation() {
        if (Thread.currentThread().isInterrupted()) throw new LayoutLimit("Mermaid layout cancelled");
    }
}
