package dev.tuink.highlight.regex;

/** A request-local work budget shared by regex rules, lexical context and styling. */
final class HighlightWork {
    private long remaining;

    HighlightWork(long remaining) {
        this.remaining = remaining;
    }

    void spend(long amount) {
        if (amount < 0 || amount > remaining) {
            remaining = 0;
            throw new Exhausted();
        }
        remaining -= amount;
    }

    long remaining() {
        return remaining;
    }

    static final class Exhausted extends RuntimeException {
        private Exhausted() {
            super(null, null, false, false);
        }
    }
}
