package dev.tuiframe.explorer.file;

import dev.tuiframe.text.TerminalText;
import java.nio.file.Path;
import java.util.Objects;

/** Lossless path spellings, independent of widget layout, clipping and rendering. */
public final class PathLabels {
    private static final String URI_MARKER = "file:";

    private PathLabels() {}

    /** Full, reversible input spelling, using a URI when terminal sanitization would change its identity. */
    public static String full(Path path) {
        Objects.requireNonNull(path, "path");
        String literal = path.toString();
        String formatted = !literal.startsWith("file:/")
            && literal.codePoints().noneMatch(Character::isISOControl) && Path.of(literal).equals(path)
            ? literal : path.toAbsolutePath().toUri().toASCIIString();
        return displayPreserves(formatted) && (!formatted.startsWith(URI_MARKER) || formatted.startsWith("file:/"))
            ? formatted : path.toAbsolutePath().toUri().toASCIIString();
    }

    /** A reversible relative spelling; unsafe native names retain the actual absolute URI. */
    public static String relative(Path base, Path path) {
        Path relative = base.toAbsolutePath().normalize().relativize(path.toAbsolutePath().normalize());
        String literal = relative.toString();
        if (literal.isEmpty()) return ".";
        return !literal.startsWith(URI_MARKER) && displayPreserves(literal)
            && Path.of(literal).equals(relative) ? literal : full(path.toAbsolutePath().normalize());
    }

    /** Uses the original path's URI component, so a raw basename never acquires a fictitious working directory. */
    public static String name(Path path) {
        Objects.requireNonNull(path, "path");
        Path leaf = path.getFileName();
        if (leaf == null) return full(path);
        String literal = leaf.toString();
        if (!literal.startsWith(URI_MARKER) && displayPreserves(literal)
            && Path.of(literal).equals(leaf)) return literal;
        String uri = path.toAbsolutePath().toUri().toASCIIString();
        if (uri.endsWith("/")) uri = uri.substring(0, uri.length() - 1);
        String component = uri.substring(uri.lastIndexOf('/') + 1);
        return URI_MARKER + component.replace(":", "%3A");
    }

    public static boolean encodedName(Path path) {
        return path != null && name(path).startsWith(URI_MARKER);
    }

    private static boolean displayPreserves(String text) {
        var cursor = TerminalText.graphemeCursor(text);
        while (cursor.advance()) {
            if (cursor.isTab()) return false;
            String sanitized = TerminalText.sanitizeGrapheme(text, cursor.start(), cursor.end());
            if (sanitized.length() != cursor.end() - cursor.start()
                || !text.regionMatches(cursor.start(), sanitized, 0, sanitized.length())) return false;
        }
        return true;
    }
}
