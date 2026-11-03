package dev.tuink.tui;

import dev.tuiframe.cell.CellText;
import dev.tuiframe.geometry.Rect;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** One cell-based layout for complete status fields and their optional actions. */
final class StatusBarLayout {
    enum Key { POSITION, TABS, SWITCH, DETAIL, PROGRESS, ACCESS, ENCODING, ENDINGS, MODE }
    /** Lower priorities shorten first; required fields outlive all optional fields. */
    record Field(Key key, String text, String compact, int priority, int compactPriority, boolean required) {
        Field { text = text == null ? "" : text; compact = compact == null ? text : compact; }
        static Field required(Key key, String text, String compact, int priority) {
            return required(key, text, compact, priority, priority);
        }
        static Field required(Key key, String text, String compact, int priority, int compactPriority) {
            return new Field(key, text, compact, priority, compactPriority, true);
        }
        static Field optional(Key key, String text, int priority) {
            return optional(key, text, text, priority, priority);
        }
        static Field optional(Key key, String text, String compact, int priority, int compactPriority) {
            return new Field(key, text, compact, priority, compactPriority, false);
        }
    }
    record Placement(String text, Map<Key, Rect> fields) {
        Placement { fields = Map.copyOf(fields); }
        Rect bounds(Key key, int row) { return fields.getOrDefault(key, new Rect(0, row, 0, 0)); }
    }
    private static final class Slot {
        final Field field;
        String text;
        Slot(Field field) { this.field = field; text = field.text(); }
        boolean reducible() { return !text.isEmpty() && (!text.equals(field.compact()) || !field.required()); }
        int reductionPriority() { return !text.equals(field.compact()) ? field.compactPriority() : field.priority(); }
        void reduce() { text = !text.equals(field.compact()) ? field.compact() : ""; }
    }
    private StatusBarLayout() {}

    static Placement place(List<Field> left, List<Field> right, int columns, int row) {
        int width = Math.max(0, columns);
        var lhs = slots(left); var rhs = slots(right);
        var all = new ArrayList<Slot>(lhs); all.addAll(rhs);
        while (requiredWidth(lhs, rhs) > width) {
            Slot next = null;
            for (Slot slot : all) if (slot.reducible() && (next == null || slot.reductionPriority() < next.reductionPriority())) next = slot;
            if (next == null) {
                // Even a tiny terminal keeps the most useful complete fields.
                for (Slot slot : all) if (!slot.text.isEmpty() && (next == null || slot.field.priority() < next.field.priority())) next = slot;
                if (next == null) break;
                next.text = "";
            } else next.reduce();
        }
        var fields = new EnumMap<Key, Rect>(Key.class);
        StringBuilder text = new StringBuilder();
        if (groupWidth(lhs, "  ") > 0) { text.append(' '); append(text, lhs, "  ", row, fields); }
        int rightWidth = groupWidth(rhs, " | ");
        if (rightWidth > 0) {
            int start = width - rightWidth - 1;
            text.append(" ".repeat(Math.max(0, start - CellText.width(text.toString()))));
            append(text, rhs, " | ", row, fields);
        }
        text.append(" ".repeat(Math.max(0, width - CellText.width(text.toString()))));
        return new Placement(text.toString(), fields);
    }
    private static List<Slot> slots(List<Field> fields) {
        return fields.stream().map(Slot::new).toList();
    }
    private static int requiredWidth(List<Slot> left, List<Slot> right) {
        int l = groupWidth(left, "  "), r = groupWidth(right, " | ");
        return (l == 0 ? 0 : l + 1) + (r == 0 ? 0 : r + 1) + (l > 0 && r > 0 ? 2 : 0);
    }
    private static int groupWidth(List<Slot> slots, String separator) {
        int width = 0, count = 0;
        for (Slot slot : slots) if (!slot.text.isEmpty()) { width += CellText.width(slot.text); count++; }
        return width + Math.max(0, count - 1) * CellText.width(separator);
    }
    private static void append(StringBuilder text, List<Slot> slots, String separator, int row, Map<Key, Rect> fields) {
        boolean first = true;
        for (Slot slot : slots) if (!slot.text.isEmpty()) {
            if (!first) text.append(separator);
            int start = CellText.width(text.toString());
            text.append(slot.text);
            fields.put(slot.field.key(), new Rect(start, row, CellText.width(slot.text), 1));
            first = false;
        }
    }
}
