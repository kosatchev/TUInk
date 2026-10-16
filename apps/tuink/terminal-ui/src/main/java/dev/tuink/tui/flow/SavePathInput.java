package dev.tuink.tui.flow;

import java.net.URI;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Objects;

/** Reversible paths at the Save As text-field boundary. */
public final class SavePathInput {
    // The JDK exposes no portable NAME_MAX query. Keep generated names within
    // the common 255-byte limit, using native Unix bytes rather than display text.
    private static final int GENERATED_NAME_BUDGET = 255;
    private static final char[] HEX = "0123456789ABCDEF".toCharArray();

    private SavePathInput() {}

    public static String format(Path path) {
        Objects.requireNonNull(path, "path");
        String literal = path.toString();
        if (!literal.startsWith("file:/") && literal.codePoints().noneMatch(Character::isISOControl)
            && Path.of(literal).equals(path)) {
            return literal;
        }
        Path absolute = path.toAbsolutePath();
        String encoded = absolute.toUri().toASCIIString();
        if (!parse(encoded).equals(absolute)) {
            throw new IllegalArgumentException("Save target cannot be represented by a lossless file URI");
        }
        return encoded;
    }

    public static Path parse(String text) {
        Objects.requireNonNull(text, "text");
        if (!text.startsWith("file:/")) {
            return Path.of(text);
        }
        URI uri = URI.create(text);
        if (!"file".equals(uri.getScheme()) || uri.isOpaque()
            || uri.getRawQuery() != null || uri.getRawFragment() != null) {
            throw new IllegalArgumentException("Save target must be an absolute file URI");
        }
        Path path = Path.of(uri);
        if (!path.isAbsolute() || !Path.of(path.toUri()).equals(path)) {
            throw new IllegalArgumentException("Save target must be a reversible absolute file URI");
        }
        return path;
    }

    /** Adds a generated suffix within the name budget, retaining the extension where it fits. */
    public static Path withNameSuffix(Path path, String suffix) {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(suffix, "suffix");
        Path leaf = Objects.requireNonNull(path.getFileName(), "file name");
        Path suffixLeaf = path.getFileSystem().getPath("x" + suffix);
        if (suffixLeaf.getNameCount() != 1 || !suffixLeaf.toString().equals("x" + suffix)) {
            throw new IllegalArgumentException("Save-name suffix must be a filename fragment");
        }
        if (leaf.toString().isEmpty()) {
            return path.resolveSibling(suffixed("", suffix));
        }
        if (path.getFileSystem() == FileSystems.getDefault()
            && path.getFileSystem().getSeparator().equals("/")) {
            byte[] name = nativeName(path);
            byte[] suffixWithPrefix = nativeName(suffixLeaf);
            byte[] suffixBytes = Arrays.copyOfRange(suffixWithPrefix, 1, suffixWithPrefix.length);
            byte[] generated = suffixed(name, suffixBytes);
            StringBuilder uri = new StringBuilder("file:///");
            for (byte value : generated) {
                int unsigned = value & 0xff;
                uri.append('%').append(HEX[unsigned >>> 4]).append(HEX[unsigned & 15]);
            }
            Path generatedLeaf = Path.of(URI.create(uri.toString())).getFileName();
            return path.resolveSibling(generatedLeaf);
        }
        // Other providers expose Unicode names. UTF-8 byte accounting is a
        // conservative bound for Windows' 255 UTF-16-unit component limit too.
        return path.resolveSibling(suffixed(leaf.toString(), suffix));
    }

    private static String suffixed(String name, String suffix) {
        int dot = name.lastIndexOf('.');
        int insertion = dot > 0 ? dot : name.length();
        int suffixSize = unicodeSize(suffix, 0, suffix.length());
        int room = suffixRoom(suffixSize);
        int firstUnitSize = name.isEmpty() ? 0 : unicodeUnitSize(name.codePointAt(0));
        int extensionEnd = unicodePrefixEnd(name, insertion, name.length(), Math.max(0, room - firstUnitSize));
        int extensionSize = unicodeSize(name, insertion, extensionEnd);
        int stemEnd = unicodePrefixEnd(name, 0, insertion, room - extensionSize);
        return name.substring(0, stemEnd) + suffix + name.substring(insertion, extensionEnd);
    }

    private static byte[] suffixed(byte[] name, byte[] suffix) {
        int insertion = name.length;
        for (int index = name.length - 1; index > 0; index--) {
            if (name[index] == '.') {
                insertion = index;
                break;
            }
        }
        int room = suffixRoom(suffix.length);
        int firstUnitSize = utf8UnitLength(name, 0, insertion);
        int extensionEnd = nativePrefixEnd(name, insertion, name.length, Math.max(0, room - firstUnitSize));
        int extensionSize = extensionEnd - insertion;
        int stemEnd = nativePrefixEnd(name, 0, insertion, room - extensionSize);
        byte[] result = new byte[stemEnd + suffix.length + extensionSize];
        System.arraycopy(name, 0, result, 0, stemEnd);
        System.arraycopy(suffix, 0, result, stemEnd, suffix.length);
        System.arraycopy(name, insertion, result, stemEnd + suffix.length, extensionSize);
        return result;
    }

    private static int suffixRoom(int suffixSize) {
        if (suffixSize >= GENERATED_NAME_BUDGET) {
            throw new IllegalArgumentException("Save-name suffix exceeds the generated filename budget");
        }
        return GENERATED_NAME_BUDGET - suffixSize;
    }

    /** File URIs encode Unix filename bytes losslessly, including bytes not representable in Java strings. */
    private static byte[] nativeName(Path path) {
        String encoded = path.toAbsolutePath().toUri().toASCIIString();
        if (encoded.endsWith("/")) encoded = encoded.substring(0, encoded.length() - 1);
        String leaf = encoded.substring(encoded.lastIndexOf('/') + 1);
        byte[] bytes = new byte[leaf.length()];
        int count = 0;
        for (int index = 0; index < leaf.length(); index++) {
            char value = leaf.charAt(index);
            if (value == '%') {
                bytes[count++] = (byte) ((Character.digit(leaf.charAt(index + 1), 16) << 4)
                    | Character.digit(leaf.charAt(index + 2), 16));
                index += 2;
            } else {
                bytes[count++] = (byte) value;
            }
        }
        return Arrays.copyOf(bytes, count);
    }

    private static int nativePrefixEnd(byte[] value, int start, int end, int budget) {
        int index = start;
        while (index < end) {
            int length = utf8UnitLength(value, index, end);
            if (length > budget) break;
            budget -= length;
            index += length;
        }
        return index;
    }

    /** Keep valid Unicode scalars together; undecodable native bytes remain individual, unchanged bytes. */
    private static int utf8UnitLength(byte[] value, int start, int end) {
        int first = value[start] & 0xff;
        if (first < 0xC2 || first > 0xF4) return 1;
        int length = first < 0xE0 ? 2 : first < 0xF0 ? 3 : 4;
        if (start + length > end) return 1;
        int second = value[start + 1] & 0xff;
        if (second < 0x80 || second > 0xBF || first == 0xE0 && second < 0xA0
            || first == 0xED && second > 0x9F || first == 0xF0 && second < 0x90
            || first == 0xF4 && second > 0x8F) return 1;
        for (int index = start + 2; index < start + length; index++) {
            if ((value[index] & 0xC0) != 0x80) return 1;
        }
        return length;
    }

    private static int unicodePrefixEnd(String value, int start, int end, int budget) {
        int index = start;
        while (index < end) {
            int codePoint = value.codePointAt(index);
            int size = unicodeUnitSize(codePoint);
            if (size > budget) break;
            budget -= size;
            index += Character.charCount(codePoint);
        }
        return index;
    }

    private static int unicodeSize(String value, int start, int end) {
        int bytes = 0;
        for (int index = start; index < end;) {
            int codePoint = value.codePointAt(index);
            bytes += unicodeUnitSize(codePoint);
            if (bytes >= GENERATED_NAME_BUDGET) return GENERATED_NAME_BUDGET;
            index += Character.charCount(codePoint);
        }
        return bytes;
    }

    private static int unicodeUnitSize(int codePoint) {
        return codePoint < 0x80 ? 1 : codePoint < 0x800 ? 2 : codePoint < 0x10000 ? 3 : 4;
    }
}
