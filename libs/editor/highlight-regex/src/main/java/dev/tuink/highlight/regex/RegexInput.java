package dev.tuink.highlight.regex;

/** Shares a linear character-access budget across all find attempts of one rule. */
final class RegexInput implements CharSequence {
    private final String text;
    private final int start;
    private final int end;
    private final Budget budget;

    RegexInput(String text) {
        this(text, 0, text.length(), new Budget(1024L + 64L * text.length()));
    }

    private RegexInput(String text, int start, int end, Budget budget) {
        this.text = text;
        this.start = start;
        this.end = end;
        this.budget = budget;
    }

    @Override
    public int length() {
        return end - start;
    }

    @Override
    public char charAt(int index) {
        if (index < 0 || index >= length()) {
            throw new IndexOutOfBoundsException(index);
        }
        if (--budget.remaining < 0) {
            throw new MatchLimitExceeded();
        }
        return text.charAt(start + index);
    }

    @Override
    public CharSequence subSequence(int from, int to) {
        if (from < 0 || to < from || to > length()) {
            throw new IndexOutOfBoundsException();
        }
        return new RegexInput(text, start + from, start + to, budget);
    }

    @Override
    public String toString() {
        return text.substring(start, end);
    }

    private static final class Budget {
        private long remaining;

        private Budget(long remaining) {
            this.remaining = remaining;
        }
    }

    static final class MatchLimitExceeded extends RuntimeException {
        private MatchLimitExceeded() {
            super(null, null, false, false);
        }
    }
}
