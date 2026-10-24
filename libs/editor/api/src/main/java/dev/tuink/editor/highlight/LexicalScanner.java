package dev.tuink.editor.highlight;

import java.util.List;
import java.util.Objects;

/**
 * Optional semantic scanning shared by syntax highlighting and bracket matching.
 * A caller owns the continuation state and the total document scan budget.
 */
public interface LexicalScanner {
    int MAX_EXCLUDED_RANGES = 4096;
    int MAX_QUOTES = 4096;

    /** Opaque, immutable continuation state belonging to one scanner. Null starts a document. */
    interface State {
        /** A stopped scan cannot be resumed as though it had reached an ordinary line boundary. */
        State UNKNOWN = new State() {};
    }

    /**
     * Scans at most {@code maxCodeUnits} UTF-16 units from the start of one line.
     * Implementations must also bound allocation and work by this prefix, without
     * traversing preceding lines or the unprocessed tail. Returned ranges exclude
     * strings and comments from bracket matching, independently of display style.
     * Output limits must be checked before allocating ranges or quotes. A caller
     * may reuse the end state only when {@link Result#contextKnown()} is true and
     * the scanned prefix covers the original line. Unknown state stays unknown,
     * including across an empty line.
     */
    Result scan(String line, int maxCodeUnits, State previous);

    record Range(int start, int end) {
        public Range {
            if (start < 0 || end < start) {
                throw new IllegalArgumentException("Invalid excluded lexical range");
            }
        }
    }

    /** Offsets of ordinary opening/closing quote delimiters; -1 denotes a truly unclosed quote. */
    record Quote(int start, int end) {
        public Quote {
            if (start < 0 || end != -1 && end < start) {
                throw new IllegalArgumentException("Invalid quote delimiters");
            }
        }
    }

    record Result(int scannedCodeUnits, State endState, List<Range> excludedRanges, List<Quote> quotes,
                  boolean contextKnown) {
        /** Compatibility constructor; callers must still verify that the complete source line was scanned. */
        public Result(int scannedCodeUnits, State endState, List<Range> excludedRanges, List<Quote> quotes) {
            this(scannedCodeUnits, endState, excludedRanges, quotes, true);
        }

        public Result {
            if (scannedCodeUnits < 0) {
                throw new IllegalArgumentException("Negative lexical scan length");
            }
            Objects.requireNonNull(excludedRanges, "excludedRanges");
            Objects.requireNonNull(quotes, "quotes");
            if (excludedRanges.size() > MAX_EXCLUDED_RANGES || quotes.size() > MAX_QUOTES) {
                throw new IllegalArgumentException("Lexical output exceeds its per-line budget");
            }
            excludedRanges = List.copyOf(excludedRanges);
            quotes = List.copyOf(quotes);
            if (!contextKnown) {
                endState = State.UNKNOWN;
            } else if (endState == State.UNKNOWN) {
                throw new IllegalArgumentException("Unknown lexical context cannot be marked complete");
            }
            for (Range range : excludedRanges) {
                if (range.end() > scannedCodeUnits) {
                    throw new IllegalArgumentException("Excluded range exceeds scanned prefix");
                }
            }
            for (Quote quote : quotes) {
                if (quote.start() >= scannedCodeUnits || quote.end() >= scannedCodeUnits) {
                    throw new IllegalArgumentException("Quote exceeds scanned prefix");
                }
            }
        }
    }
}
