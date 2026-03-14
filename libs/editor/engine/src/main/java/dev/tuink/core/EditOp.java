package dev.tuink.core;

/**
 * Edit operations used by undo/redo and incremental gutter markers.
 */
public sealed interface EditOp permits EditOp.ReplaceRange {
    Position start();

    String deletedText();

    String insertedText();

    default int startLine() {
        return start().line();
    }

    default int endLineBefore() {
        return advanceLine(start().line(), deletedText());
    }

    default int endLineAfter() {
        return advanceLine(start().line(), insertedText());
    }

    static ReplaceRange insert(Position at, String text) {
        return new ReplaceRange(at, "", text);
    }

    static ReplaceRange delete(Position at, String deletedText) {
        return new ReplaceRange(at, deletedText, "");
    }

    static ReplaceRange replace(Position at, String deletedText, String insertedText) {
        return new ReplaceRange(at, deletedText, insertedText);
    }

    private static int advanceLine(int line, String text) {
        int current = Math.max(0, line);
        if (text == null || text.isEmpty()) {
            return current;
        }
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                current++;
            }
        }
        return current;
    }

    /**
     * Replace text in range [start, start+deletedText) with insertedText.
     */
    record ReplaceRange(Position start, String deletedText, String insertedText) implements EditOp {
        public ReplaceRange {
            if (start == null) {
                throw new IllegalArgumentException("start must not be null");
            }
            deletedText = deletedText == null ? "" : deletedText;
            insertedText = insertedText == null ? "" : insertedText;
        }
    }
}
