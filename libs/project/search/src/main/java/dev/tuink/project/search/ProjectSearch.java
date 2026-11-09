package dev.tuink.project.search;

import dev.tuink.core.SearchMatcher;
import dev.tuink.core.SearchOperationContext;
import dev.tuink.editor.document.DocumentId;
import dev.tuink.editor.document.DocumentSnapshot;
import dev.tuink.editor.search.SearchOptions;
import dev.tuink.editor.storage.DocumentOpenMode;
import dev.tuink.editor.storage.DocumentResource;
import dev.tuink.editor.storage.ResourceVersion;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/** Detached, bounded text search. Hosts supply sources, execution and event delivery. */
public final class ProjectSearch {
    public static final int MAX_QUERY_LENGTH = 4096;
    public static final int MAX_LINE_LENGTH = 1024 * 1024;
    public static final long RESULT_MEMORY_LIMIT = 8 * 1024 * 1024L;
    private ProjectSearch() {}

    public record Request(String query, SearchOptions options, int maximumMatches) {
        public Request {
            Objects.requireNonNull(query); Objects.requireNonNull(options);
            if (query.isEmpty() || query.length() > MAX_QUERY_LENGTH || query.indexOf('\n') >= 0 || query.indexOf('\r') >= 0)
                throw new IllegalArgumentException("Enter 1–4096 characters on one line");
            if (maximumMatches < 1 || maximumMatches > 100_000)
                throw new IllegalArgumentException("Result limit must be between 1 and 100000");
        }
    }

    public interface Control {
        void checkpoint();
    }
    @FunctionalInterface public interface Lines {
        void accept(int line, CharSequence text) throws IOException;
    }
    @FunctionalInterface public interface SourceVisitor { void accept(Source source) throws IOException; }
    @FunctionalInterface public interface Sources { void visit(Control control, SourceVisitor visitor) throws IOException; }
    public interface Source {
        String key();
        String label();
        DocumentResource resource();
        default DocumentId documentId() { return null; }
        default long revision() { return -1; }
        ReadVersion read(Control control, Lines lines) throws IOException;
    }
    public record ReadVersion(ResourceVersion version, DocumentOpenMode mode) {
        public ReadVersion { Objects.requireNonNull(mode); }
    }
    public record Target(String key, String label, DocumentResource resource, DocumentId documentId,
                         long revision, ReadVersion readVersion) {}
    public record Match(Target target, int line, int start, int end, String snippet, int snippetOffset) {}
    public enum Outcome { COMPLETE, PARTIAL, LIMIT, CANCELLED, FAILED }
    public sealed interface Event permits BufferSearched, Matches, Progress, Issue, Finished {}
    /** An eligible live buffer was visited, including one with no matches. */
    public record BufferSearched(DocumentId documentId) implements Event {}
    public record Matches(List<Match> matches) implements Event {
        public Matches { matches = List.copyOf(matches); }
    }
    public record Progress(long files, long matches, long skipped) implements Event {}
    public record Issue(String label, String reason) implements Event {}
    public record Finished(Outcome outcome, long files, long matches, long skipped, String reason) implements Event {}
    /** Delivery may apply cancellable backpressure; it must never silently drop accepted matches. */
    @FunctionalInterface public interface Events { void accept(Event event); }

    public static Source snapshot(String key, String label, DocumentResource resource, DocumentSnapshot snapshot,
                                  DocumentOpenMode mode) {
        Objects.requireNonNull(snapshot);
        return new Source() {
            public String key() { return key; }
            public String label() { return label; }
            public DocumentResource resource() { return resource; }
            public DocumentId documentId() { return snapshot.documentId(); }
            public long revision() { return snapshot.revision(); }
            public ReadVersion read(Control control, Lines lines) throws IOException {
                for (int line = 0; line < snapshot.lineCount(); line++) {
                    control.checkpoint();
                    lines.accept(line, snapshot.lineText(line));
                }
                return new ReadVersion(null, mode);
            }
        };
    }

    public static void run(Request request, Sources sources, BooleanSupplier cancelled, Events events) {
        SearchOperationContext context = SearchOperationContext.cancellable(100_000_000L, cancelled);
        State state = new State(request, context, events);
        Finished completion;
        try {
            SearchMatcher matcher = SearchMatcher.compile(request.query(), request.options(), context);
            sources.visit(context::checkpoint, source -> state.search(source, matcher));
            completion = new Finished(state.skipped == 0 ? Outcome.COMPLETE : Outcome.PARTIAL,
                state.files, state.matches, state.skipped, "");
        } catch (Limit stop) {
            completion = new Finished(Outcome.LIMIT, state.files, state.matches, state.skipped,
                "Results limited; narrow the query or increase the result limit");
        } catch (CancellationException stop) {
            // The host owns the stop indication and rejects superseded runs.
            return;
        } catch (IOException | RuntimeException failure) {
            completion = new Finished(Outcome.FAILED, state.files, state.matches, state.skipped,
                Objects.toString(failure.getMessage(), "Search failed"));
        }
        try {
            // Terminal delivery can block or lose its generation just like match delivery.
            context.checkpoint();
            events.accept(completion);
        } catch (CancellationException stop) {
            // Never let a superseded completion escape to an executor's stderr handler.
        }
    }

    private static final class Limit extends RuntimeException {}
    private static final class State {
        final Request request;
        final SearchOperationContext context;
        final Events events;
        long files, matches, skipped, retained;
        State(Request request, SearchOperationContext context, Events events) {
            this.request = request; this.context = context; this.events = events;
        }
        void search(Source source, SearchMatcher matcher) {
            context.checkpoint();
            if (source.documentId() != null) events.accept(new BufferSearched(source.documentId()));
            ArrayList<PendingMatch> found = new ArrayList<>();
            long[] bytes = {0};
            boolean[] limited = {false};
            try {
                ReadVersion version = source.read(context::checkpoint, (line, text) -> {
                    context.checkpoint();
                    if (text.length() > MAX_LINE_LENGTH) throw new IOException("Logical line exceeds 1 Mi UTF-16 units");
                    if (limited[0]) return;
                    matcher.scanMatches(new CheckedText(text, context), (start, end) -> {
                        int left = Math.max(0, start - 48);
                        int right = Math.min(text.length(), Math.max(start + 160, Math.min(end + 48, start + 240)));
                        if (left > 0 && Character.isLowSurrogate(text.charAt(left))) left--;
                        if (right < text.length() && right > 0 && Character.isHighSurrogate(text.charAt(right - 1))) right--;
                        long cost = 192L + (right - left) * 2L;
                        if (matches + found.size() >= request.maximumMatches()
                            || retained + bytes[0] + cost > RESULT_MEMORY_LIMIT) { limited[0] = true; return false; }
                        String snippet = text.subSequence(left, right).toString();
                        found.add(new PendingMatch(line, start, end, snippet, left));
                        bytes[0] += cost;
                        return true;
                    }, context);
                });
                context.checkpoint();
                Target target = new Target(source.key(), source.label(), source.resource(), source.documentId(),
                    source.revision(), version);
                for (int first = 0; first < found.size(); first += 128) {
                    context.checkpoint();
                    ArrayList<Match> batch = new ArrayList<>();
                    for (int i = first; i < Math.min(found.size(), first + 128); i++) {
                        PendingMatch match = found.get(i);
                        batch.add(new Match(target, match.line, match.start, match.end, match.snippet, match.offset));
                    }
                    events.accept(new Matches(batch));
                    matches += batch.size();
                }
                retained += bytes[0];
                files++;
                events.accept(new Progress(files, matches, skipped));
                if (limited[0]) throw new Limit();
            } catch (IOException failure) {
                skipped++;
                if (skipped <= 100) events.accept(new Issue(source.label(), failure.getMessage()));
                events.accept(new Progress(files, matches, skipped));
            }
        }
    }
    private record PendingMatch(int line, int start, int end, String snippet, int offset) {}
    private static final class CheckedText implements CharSequence {
        final CharSequence value; final SearchOperationContext context; int reads;
        CheckedText(CharSequence value, SearchOperationContext context) { this.value = value; this.context = context; }
        public int length() { return value.length(); }
        public char charAt(int index) { if ((reads++ & 4095) == 0) context.checkpoint(); return value.charAt(index); }
        public CharSequence subSequence(int start, int end) { return value.subSequence(start, end); }
    }
}
