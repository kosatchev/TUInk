package dev.tuink.tui;

import dev.tuink.editor.document.DocumentSnapshot;
import dev.tuink.editor.storage.FrozenDocumentSource;
import dev.tuink.markdown.MarkdownBlock;
import dev.tuink.markdown.MarkdownDocument;
import dev.tuink.markdown.MarkdownInline;
import dev.tuink.render.markdown.MarkdownLayout;
import dev.tuiframe.memory.RenderMemoryBudget;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;

/** Window-wide conservative retention ledger. Identity sharing never requires materializing text. */
final class MarkdownPreviewBudget implements AutoCloseable {
    record Limits(long totalBytes, long sourceBytes, long modelBytes, long layoutBytes) {
        static final Limits DEFAULT = new Limits(64L << 20, 32L << 20, 16L << 20, 16L << 20);
        Limits {
            if (totalBytes < 1 || sourceBytes < 1 || modelBytes < 1 || layoutBytes < 1)
                throw new IllegalArgumentException("Preview memory budgets must be positive");
        }
    }
    static final class Refused extends IllegalArgumentException {
        private static final long serialVersionUID = 1L;
        Refused(String message) { super(message); }
    }
    enum Kind { SOURCE, MODEL, LAYOUT }
    private record Item(Kind kind, Object identity, long bytes) {}
    private static final class Entry {
        final long bytes; int references = 1;
        Entry(long bytes) { this.bytes = bytes; }
    }
    final class Lease implements AutoCloseable {
        private List<Item> items;
        private Lease(List<Item> items) { this.items = items; }
        @Override public void close() {
            synchronized (MarkdownPreviewBudget.this) {
                if (items == null) return;
                for (Item item : items) {
                    var map = entries.get(item.kind().ordinal());
                    Entry entry = map.get(item.identity());
                    if (entry != null && --entry.references == 0) {
                        map.remove(item.identity()); used[item.kind().ordinal()] -= entry.bytes;
                    }
                }
                items = null;
            }
        }
    }
    private final Limits limits;
    private final List<IdentityHashMap<Object, Entry>> entries = List.of(
        new IdentityHashMap<>(), new IdentityHashMap<>(), new IdentityHashMap<>());
    private final long[] used = new long[3];
    private boolean closed;

    MarkdownPreviewBudget(Limits limits) { this.limits = limits; }

    /** Shared layout-pool admission for geometry and raster caches; it adds no independent allowance. */
    RenderMemoryBudget renderMemoryBudget() { return this::reserveRender; }

    private synchronized RenderMemoryBudget.Reservation reserveRender(long bytes) {
        if (bytes < 0) throw new IllegalArgumentException("Render allocation must be nonnegative");
        int kind = Kind.LAYOUT.ordinal();
        if (closed || bytes > limits.layoutBytes() - used[kind] || bytes > limits.totalBytes() - usedBytes()) return null;
        RenderAllocation allocation = new RenderAllocation(bytes);
        RenderReservation reservation = new RenderReservation(allocation);
        used[kind] += bytes;
        return reservation;
    }

    private static final class RenderAllocation {
        final long bytes; int references = 1;
        RenderAllocation(long bytes) { this.bytes = bytes; }
    }
    private final class RenderReservation implements RenderMemoryBudget.Reservation {
        private RenderAllocation allocation;
        private final long bytes;
        RenderReservation(RenderAllocation allocation) { this.allocation = allocation; bytes = allocation.bytes; }
        @Override public long bytes() { return bytes; }
        @Override public RenderMemoryBudget.Reservation retain() {
            synchronized (MarkdownPreviewBudget.this) {
                if (allocation == null) throw new IllegalStateException("Render reservation is closed");
                if (allocation.references == Integer.MAX_VALUE) throw new IllegalStateException("Too many render owners");
                RenderReservation retained = new RenderReservation(allocation);
                allocation.references++; return retained;
            }
        }
        @Override public void close() {
            synchronized (MarkdownPreviewBudget.this) {
                if (allocation == null) return;
                if (--allocation.references == 0) used[Kind.LAYOUT.ordinal()] -= allocation.bytes;
                allocation = null;
            }
        }
    }

    Lease source(FrozenDocumentSource source) {
        return retain(sourceItems(source));
    }
    Lease sourceText(DocumentSnapshot text) { return retain(textItems(text)); }
    private static List<Item> textItems(DocumentSnapshot text) {
        var items = new ArrayList<Item>();
        items.add(new Item(Kind.SOURCE, text, 96L + 8L * text.lineCount()));
        for (var line : text.immutableLines())
            items.add(new Item(Kind.SOURCE, line, 64L + 4L * line.length() + 64L * line.retainedNodes()));
        return unique(items);
    }
    private static List<Item> sourceItems(FrozenDocumentSource source) {
        var items = new ArrayList<>(textItems(source.text()));
        items.add(new Item(Kind.SOURCE, source.metadata(), 96L + 16L * source.metadata().lineSeparators().size()));
        return unique(items);
    }

    /** Replacing metadata must not require space for two complete separator tables. */
    synchronized Lease replaceSource(Lease previous, FrozenDocumentSource source) {
        if (previous == null || previous.items == null) return source(source);
        if (closed) throw new IllegalStateException("Preview memory budget is closed");
        var items = sourceItems(source);
        var old = new IdentityHashMap<Object, Boolean>();
        long change = 0;
        var entries = this.entries.get(Kind.SOURCE.ordinal());
        for (Item item : previous.items) {
            if (item.kind() != Kind.SOURCE) throw new IllegalArgumentException("Expected a source snapshot lease");
            old.put(item.identity(), Boolean.TRUE);
            Entry entry = entries.get(item.identity());
            if (entry.references == 1) change -= entry.bytes;
        }
        for (Item item : items) {
            Entry entry = entries.get(item.identity());
            if (entry == null || old.containsKey(item.identity()) && entry.references == 1) change += item.bytes();
        }
        if (used[0] + change > limits.sourceBytes()) throw new Refused("Preview source snapshot memory budget exceeded");
        if (usedBytes() + change > limits.totalBytes()) throw new Refused("Preview shared memory budget exceeded");
        previous.close();
        return retain(items);
    }

    Lease model(MarkdownDocument document) {
        return retain(List.of(new Item(Kind.MODEL, document, modelBytes(document))));
    }

    Lease result(MarkdownLayout.Result result) {
        long bytes = 128L;
        var strings = new IdentityHashMap<String, Boolean>();
        for (var line : result.lines()) {
            bytes += 160L + 80L * line.runs().size();
            if (strings.put(line.text(), Boolean.TRUE) == null) bytes += 40L + 2L * line.text().length();
            if (strings.put(line.copyText(), Boolean.TRUE) == null) bytes += 40L + 2L * line.copyText().length();
        }
        return retain(List.of(new Item(Kind.MODEL, result.document(), modelBytes(result.document())),
            new Item(Kind.LAYOUT, result, bytes)));
    }

    synchronized long usedBytes() { return used[0] + used[1] + used[2]; }
    synchronized long usedBytes(Kind kind) { return used[kind.ordinal()]; }

    private synchronized Lease retain(List<Item> items) {
        if (closed) throw new IllegalStateException("Preview memory budget is closed");
        long[] extra = new long[3];
        for (Item item : items) if (!entries.get(item.kind().ordinal()).containsKey(item.identity()))
            extra[item.kind().ordinal()] += item.bytes();
        if (used[0] + extra[0] > limits.sourceBytes()) throw new Refused("Preview source snapshot memory budget exceeded");
        if (used[1] + extra[1] > limits.modelBytes()) throw new Refused("Preview parsed model memory budget exceeded");
        if (used[2] + extra[2] > limits.layoutBytes()) throw new Refused("Preview layout memory budget exceeded");
        if (usedBytes() + extra[0] + extra[1] + extra[2] > limits.totalBytes())
            throw new Refused("Preview shared memory budget exceeded");
        for (Item item : items) {
            var map = entries.get(item.kind().ordinal());
            Entry entry = map.get(item.identity());
            if (entry == null) { map.put(item.identity(), new Entry(item.bytes())); used[item.kind().ordinal()] += item.bytes(); }
            else entry.references++;
        }
        return new Lease(items);
    }

    private static List<Item> unique(List<Item> items) {
        var seen = new IdentityHashMap<Object, Boolean>();
        return items.stream().filter(item -> seen.put(item.identity(), Boolean.TRUE) == null).toList();
    }

    private static long modelBytes(MarkdownDocument document) {
        var counter = new ModelCounter();
        counter.string(document.projection().text());
        counter.bytes += 128L + 64L * document.projection().mappings().size();
        counter.blocks(document.blocks());
        return counter.bytes;
    }
    private static final class ModelCounter {
        long bytes;
        final IdentityHashMap<String, Boolean> strings = new IdentityHashMap<>();
        void string(String text) { if (strings.put(text, Boolean.TRUE) == null) bytes += 40L + 2L * text.length(); }
        void blocks(List<MarkdownBlock> blocks) {
            for (var block : blocks) {
                bytes += 192;
                if (block instanceof MarkdownBlock.Paragraph n) inlines(n.content());
                else if (block instanceof MarkdownBlock.Heading n) inlines(n.content());
                else if (block instanceof MarkdownBlock.Quote n) blocks(n.blocks());
                else if (block instanceof MarkdownBlock.ListBlock n) {
                    for (var item : n.items()) { bytes += 128; blocks(item.blocks()); }
                } else if (block instanceof MarkdownBlock.Table n) {
                    for (var row : n.rows()) { bytes += 128; for (var cell : row.cells()) { bytes += 128; inlines(cell.content()); } }
                } else if (block instanceof MarkdownBlock.Code n) { string(n.text()); string(n.language()); }
                else if (block instanceof MarkdownBlock.Math n) string(n.expression());
                else if (block instanceof MarkdownBlock.Mermaid n) string(n.code());
                else if (block instanceof MarkdownBlock.Raw n) string(n.text());
            }
        }
        void inlines(List<MarkdownInline> inlines) {
            for (var inline : inlines) {
                bytes += 160;
                if (inline instanceof MarkdownInline.Text n) string(n.text());
                else if (inline instanceof MarkdownInline.Code n) string(n.text());
                else if (inline instanceof MarkdownInline.Math n) string(n.expression());
                else if (inline instanceof MarkdownInline.Styled n) inlines(n.content());
                else if (inline instanceof MarkdownInline.Link n) { string(n.destination()); string(n.title()); inlines(n.content()); }
            }
        }
    }
    @Override public synchronized void close() {
        // Closing admission is not proof that a cancelled provider has stopped reading its input.
        // Existing owners release their leases when they actually stop retaining those objects.
        closed = true;
    }
}
