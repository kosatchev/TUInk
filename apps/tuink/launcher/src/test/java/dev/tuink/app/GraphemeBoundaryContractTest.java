package dev.tuink.app;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

class GraphemeBoundaryContractTest {
    @Test
    void editingAndTerminalMetricsAgreeOnOriginalTextBoundaries() throws ReflectiveOperationException {
        // These modules are implementation dependencies of the app's facade.
        // Compare their runtime contracts without exporting internal types.
        Class<?> editingType = Class.forName("dev.tuink.unicode.GraphemeText");
        Class<?> terminalType = Class.forName("dev.tuiframe.text.TerminalText");
        for (String text : List.of("", "plain\ttext", "Привет", "a\u0301b", "中🙂",
            "👩‍💻🇷🇺", "\u0301a", "a" + "\u0301".repeat(300), "x".repeat(8192) + "🙂")) {
            Object editing = editingType.getMethod("of", String.class).invoke(null, text);
            Object terminal = terminalType.getMethod("metrics", String.class, int.class).invoke(null, text, 4);
            assertEquals(editingType.getMethod("graphemeCount").invoke(editing),
                terminal.getClass().getMethod("graphemeCount").invoke(terminal), text);
            for (int offset = -1; offset <= text.length() + 1; offset++) {
                for (String method : List.of("isBoundary", "previousBoundary", "nextBoundary",
                    "floorBoundary", "ceilBoundary")) {
                    assertEquals(editingType.getMethod(method, int.class).invoke(editing, offset),
                        terminal.getClass().getMethod(method, int.class).invoke(terminal, offset),
                        method + " at UTF-16 offset " + offset + " in " + text);
                }
            }
        }
    }
}
