package dev.tuink.markdown;

import dev.tuink.editor.document.TextRange;
import dev.tuink.editor.document.TextPosition;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/** Internal heading/link navigation over immutable semantic content; performs no resource access. */
public final class MarkdownNavigation {
    private static final int MAX_DEPTH = 128;
    private MarkdownNavigation() {}

    public record Heading(String anchor, String label, TextRange source, int projectionOffset) {
        public Heading {
            Objects.requireNonNull(anchor, "anchor");
            Objects.requireNonNull(label, "label");
            Objects.requireNonNull(source, "source");
            if (projectionOffset < 0) throw new IllegalArgumentException("Negative heading offset");
        }
    }

    /** Unicode slugs use locale-independent lowercase and unique duplicate suffixes. */
    public static List<Heading> headings(MarkdownDocument document) {
        Objects.requireNonNull(document, "document");
        ArrayList<Heading> result = new ArrayList<>();
        NavigableMap<TextPosition, Integer> offsets = new TreeMap<>();
        for (TextProjection.Mapping mapping : document.projection().mappings())
            offsets.merge(mapping.source().start(), mapping.start(), Math::min);
        collect(document, document.blocks(), result, new HashSet<>(), new HashMap<>(), offsets, 0);
        return List.copyOf(result);
    }

    /** Returns only a link enclosing the semantic character at the caret, including table links. */
    public static MarkdownInline.Link linkAt(MarkdownDocument document, int offset) {
        Objects.requireNonNull(document, "document");
        if (document.projection().text().isEmpty()) return null;
        int at = Math.max(0, Math.min(offset, document.projection().text().length() - 1));
        TextRange source = document.projection().sourceAt(at);
        return source == null ? null : find(document.blocks(), source, 0);
    }

    /** Resolves a local #fragment; paths, network addresses and invalid UTF-8 stay with the host. */
    public static Heading resolveAnchor(MarkdownDocument document, String destination) {
        Objects.requireNonNull(document, "document");
        if (destination == null || !destination.startsWith("#") || destination.length() == 1) return null;
        String anchor = decodeFragment(destination.substring(1));
        if (anchor == null) return null;
        for (Heading heading : headings(document)) if (heading.anchor().equals(anchor)) return heading;
        return null;
    }

    private static void collect(MarkdownDocument document, List<MarkdownBlock> blocks, List<Heading> headings,
                                Set<String> used, Map<String, Integer> nextSuffix,
                                NavigableMap<TextPosition, Integer> offsets, int depth) {
        if (depth > MAX_DEPTH) return;
        for (MarkdownBlock block : blocks) {
            if (block instanceof MarkdownBlock.Heading heading) {
                String label = TextProjection.from(List.of(heading)).text();
                String base = slug(label);
                int suffix = nextSuffix.getOrDefault(base, 0);
                String unique = suffix == 0 ? base : base + "-" + suffix;
                while (!used.add(unique)) unique = base + "-" + (++suffix);
                nextSuffix.put(base, suffix + 1);
                headings.add(new Heading(unique, label, heading.source(), offset(document, heading.source(), offsets)));
            } else if (block instanceof MarkdownBlock.Quote quote)
                collect(document, quote.blocks(), headings, used, nextSuffix, offsets, depth + 1);
            else if (block instanceof MarkdownBlock.ListBlock list)
                for (MarkdownBlock.Item item : list.items()) collect(document, item.blocks(), headings, used, nextSuffix, offsets, depth + 1);
        }
    }

    private static int offset(MarkdownDocument document, TextRange source, NavigableMap<TextPosition, Integer> offsets) {
        var next = offsets.ceilingEntry(source.start());
        return next == null ? document.projection().text().length() : next.getValue();
    }

    private static String slug(String label) {
        StringBuilder result = new StringBuilder();
        label.strip().toLowerCase(Locale.ROOT).codePoints().forEach(codepoint -> {
            int type = Character.getType(codepoint);
            if (Character.isLetterOrDigit(codepoint) || codepoint == '-' || codepoint == '_'
                || type == Character.NON_SPACING_MARK || type == Character.COMBINING_SPACING_MARK)
                result.appendCodePoint(codepoint);
            else if (Character.isWhitespace(codepoint)) result.append('-');
        });
        return result.toString();
    }

    private static MarkdownInline.Link find(List<MarkdownBlock> blocks, TextRange source, int depth) {
        if (depth > MAX_DEPTH) return null;
        for (MarkdownBlock block : blocks) {
            if (!contains(block.source(), source)) continue;
            MarkdownInline.Link found = null;
            if (block instanceof MarkdownBlock.Paragraph paragraph) found = findInline(paragraph.content(), source, depth + 1);
            else if (block instanceof MarkdownBlock.Heading heading) found = findInline(heading.content(), source, depth + 1);
            else if (block instanceof MarkdownBlock.Quote quote) found = find(quote.blocks(), source, depth + 1);
            else if (block instanceof MarkdownBlock.ListBlock list) {
                for (MarkdownBlock.Item item : list.items()) {
                    found = find(item.blocks(), source, depth + 1);
                    if (found != null) break;
                }
            } else if (block instanceof MarkdownBlock.Table table) {
                for (MarkdownBlock.Row row : table.rows()) {
                    for (MarkdownBlock.Cell cell : row.cells()) {
                        found = findInline(cell.content(), source, depth + 1);
                        if (found != null) break;
                    }
                    if (found != null) break;
                }
            }
            if (found != null) return found;
        }
        return null;
    }

    private static MarkdownInline.Link findInline(List<MarkdownInline> inlines, TextRange source, int depth) {
        if (depth > MAX_DEPTH) return null;
        for (MarkdownInline inline : inlines) {
            if (!contains(inline.source(), source)) continue;
            if (inline instanceof MarkdownInline.Link link) {
                MarkdownInline.Link nested = findInline(link.content(), source, depth + 1);
                return nested == null ? link : nested;
            }
            if (inline instanceof MarkdownInline.Styled styled) {
                MarkdownInline.Link found = findInline(styled.content(), source, depth + 1);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static boolean contains(TextRange outer, TextRange inner) {
        return outer.start().compareTo(inner.start()) <= 0 && outer.end().compareTo(inner.end()) >= 0;
    }

    private static String decodeFragment(String source) {
        StringBuilder result = new StringBuilder(source.length());
        for (int at = 0; at < source.length();) {
            char c = source.charAt(at);
            if (c != '%') {
                if (Character.isISOControl(c) || Character.isLowSurrogate(c)) return null;
                if (Character.isHighSurrogate(c)) {
                    if (at + 1 == source.length() || !Character.isLowSurrogate(source.charAt(at + 1))) return null;
                    result.append(c).append(source.charAt(at + 1)); at += 2;
                } else { result.append(c); at++; }
                continue;
            }
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            while (at < source.length() && source.charAt(at) == '%') {
                if (at + 2 >= source.length()) return null;
                int high = Character.digit(source.charAt(at + 1), 16), low = Character.digit(source.charAt(at + 2), 16);
                if (high < 0 || low < 0) return null;
                bytes.write((high << 4) | low); at += 3;
            }
            try {
                result.append(StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes.toByteArray())));
            } catch (CharacterCodingException malformed) { return null; }
        }
        if (result.codePoints().anyMatch(Character::isISOControl)) return null;
        return result.toString();
    }
}
