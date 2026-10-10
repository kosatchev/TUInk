package dev.tuink.render;

import dev.tuink.core.CommentSyntaxProfile;
import java.util.List;
import java.util.Objects;

/**
 * Optional semantic scanning shared by syntax highlighting and bracket matching.
 * A caller owns the continuation state and the total document scan budget.
 */
public interface LineLexicalScanner {
    /** Shared conservative fallback for prose and highlighters without semantic tokenization. */
    static LineLexicalScanner generic() {
        return new GenericLineLexicalScanner(CommentSyntaxProfile.GENERIC);
    }

    /** Conservative prose scanner preserving Markdown list markers and HTML comments. */
    static LineLexicalScanner markdownProse() {
        return new GenericLineLexicalScanner(CommentSyntaxProfile.MARKDOWN);
    }

    /** Opaque, immutable continuation state belonging to one scanner. Null starts a document. */
    interface State {
    }

    /**
     * Scans at most {@code maxCodeUnits} UTF-16 units from the start of one line.
     * Implementations must also bound allocation and work by this prefix, without
     * traversing preceding lines or the unprocessed tail. Returned ranges exclude
     * strings and comments from bracket matching, independently of display style.
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

    record Result(int scannedCodeUnits, State endState, List<Range> excludedRanges, List<Quote> quotes) {
        public Result {
            if (scannedCodeUnits < 0) {
                throw new IllegalArgumentException("Negative lexical scan length");
            }
            excludedRanges = List.copyOf(Objects.requireNonNull(excludedRanges, "excludedRanges"));
            quotes = List.copyOf(Objects.requireNonNull(quotes, "quotes"));
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
