package dev.tuink.tui;

import dev.tuiframe.geometry.Rect;
import java.util.List;
import static dev.tuink.tui.StatusBarLayout.Field;
import static dev.tuink.tui.StatusBarLayout.Key;

/** Footer text and complete-label hit regions are prepared together. */
final class DocumentStatusBarView {
    private DocumentStatusBarView() {}

    record Reading(String position, String compactPosition, String tabs, String shortcut, String search) {}

    static Placement place(Reading left, String access, String encoding, String endings, boolean reading,
                           int columns, int row) {
        var fields = List.of(
            Field.required(Key.POSITION, left.position(), left.compactPosition(), 70),
            Field.required(Key.TABS, left.tabs(), left.tabs(), 80),
            Field.required(Key.SWITCH, left.shortcut(), left.shortcut().isEmpty() ? "" : "Ctrl+P", 100, 20),
            Field.required(Key.DETAIL, left.search(), left.search(), 65));
        var right = List.of(
            Field.optional(Key.ACCESS, access, 40),
            Field.optional(Key.ENCODING, "ENC:" + encoding, 60),
            Field.optional(Key.ENDINGS, "EOL:" + endings, 30),
            Field.required(Key.MODE, reading ? "READ" : "INS", reading ? "READ" : "INS", 90));
        var placed = StatusBarLayout.place(fields, right, columns, row);
        return new Placement(placed.text(), placed.bounds(Key.ENCODING, row), placed.bounds(Key.ENDINGS, row));
    }
    record Placement(String text, Rect encoding, Rect endings) {}
}
