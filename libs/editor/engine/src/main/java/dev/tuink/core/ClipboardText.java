package dev.tuink.core;

/** Prepares clipboard text without publishing changes to the model, view or clipboard. */
final class ClipboardText {
    private static final long OVERHEAD = 16 * 1024;

    private ClipboardText() {}

    static String copy(TextModel model, Position start, Position end) {
        Runtime runtime = Runtime.getRuntime();
        long available = Math.max(0, runtime.maxMemory() - (runtime.totalMemory() - runtime.freeMemory()));
        long reserve = Math.max(1024 * 1024, Math.min(8 * 1024 * 1024, runtime.maxMemory() / 16));
        return copy(model, start, end, Math.max(0, available - reserve));
    }

    static String copy(TextModel model, Position start, Position end, long budget) {
        try {
            long length = model.textRangeLength(start, end);
            Position a = model.clamp(start);
            Position b = model.clamp(end);
            // An already materialized whole immutable line can be returned by
            // reference. An edited block tree still needs admitted allocation.
            boolean wholeLine = a.line() == b.line() && Math.min(a.col(), b.col()) == 0
                && Math.max(a.col(), b.col()) == model.lineLength(a.line());
            String existing = wholeLine ? model.lineText(a.line()).cachedString() : null;
            if (existing != null) return existing;
            // The exact-capacity builder can widen to UTF-16. Include its old
            // Latin-1 array, the finished String and temporary compression storage.
            if (length != 0 && (length > (Integer.MAX_VALUE - 8L) / 2
                || 6 * length + OVERHEAD > budget)) {
                throw new ClipboardLimitException();
            }
            return model.getTextRange(start, end);
        } catch (OutOfMemoryError exhausted) {
            throw new ClipboardLimitException();
        }
    }
}
