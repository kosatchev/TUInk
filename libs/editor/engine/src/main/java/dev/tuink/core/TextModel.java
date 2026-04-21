package dev.tuink.core;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Editing operations and persistence over a {@link TextBuffer}.
 */
public class TextModel {
    private static final int MAX_DISPLAY_METRICS_LINES = 200_000;
    private static final int MAX_DISPLAY_METRICS_WEIGHT = 8_388_608;
    private final TextBuffer buffer;
    private static final Charset FALLBACK_CHARSET = Charset.forName("windows-1251");
    private static final Set<PosixFilePermission> NEW_FILE_PERMISSIONS = Set.of(
        PosixFilePermission.OWNER_READ,
        PosixFilePermission.OWNER_WRITE,
        PosixFilePermission.GROUP_READ,
        PosixFilePermission.GROUP_WRITE,
        PosixFilePermission.OTHERS_READ,
        PosixFilePermission.OTHERS_WRITE
    );
    private int cachedVisualWidthTabWidth = -1;
    private int cachedMaxVisualLineWidth = 0;
    private boolean maxVisualWidthDirty = true;
    private final Map<WrapStartsKey, List<Integer>> wrapStartsCache = new HashMap<>();
    private final Map<WrapLayoutKey, int[]> wrapPrefixCache = new HashMap<>();
    private final Map<Integer, DisplayMetricsEntry> displayMetricsCache =
        new LinkedHashMap<>(256, 0.75f, true);
    private int displayMetricsWeight = 0;
    private long revision = 0L;

    public TextModel(TextBuffer buffer) {
        this.buffer = buffer;
    }

    public int lineCount() {
        return buffer.lineCount();
    }

    public String getLine(int line) {
        return buffer.getLine(line);
    }

    public Position insertChar(Position position, char ch) {
        String line = buffer.getLine(position.line());
        int col = Math.min(position.col(), line.length());
        String updated = line.substring(0, col) + ch + line.substring(col);
        buffer.setLine(position.line(), updated);
        invalidateVisualWidthCache();
        return new Position(position.line(), col + 1);
    }

    public Position insertNewline(Position position) {
        String line = buffer.getLine(position.line());
        int col = Math.min(position.col(), line.length());
        String left = line.substring(0, col);
        String right = line.substring(col);
        buffer.setLine(position.line(), left);
        buffer.insertLine(position.line() + 1, right);
        invalidateVisualWidthCache();
        return new Position(position.line() + 1, 0);
    }

    public Position deleteBackward(Position position) {
        if (position.col() > 0) {
            String line = buffer.getLine(position.line());
            int col = Math.min(position.col(), line.length());
            int end = DisplayText.ceilBoundary(line, col);
            int previous = DisplayText.previousBoundary(line, end);
            String updated = line.substring(0, previous) + line.substring(end);
            buffer.setLine(position.line(), updated);
            invalidateVisualWidthCache();
            return new Position(position.line(), previous);
        }

        if (position.line() == 0) {
            return position;
        }

        String current = buffer.removeLine(position.line());
        int prevLineIndex = position.line() - 1;
        String prev = buffer.getLine(prevLineIndex);
        buffer.setLine(prevLineIndex, prev + current);
        invalidateVisualWidthCache();
        return new Position(prevLineIndex, prev.length());
    }

    public Position deleteForward(Position position) {
        String line = buffer.getLine(position.line());
        int col = Math.min(position.col(), line.length());

        if (col < line.length()) {
            int start = DisplayText.floorBoundary(line, col);
            int next = DisplayText.nextBoundary(line, start);
            String updated = line.substring(0, start) + line.substring(next);
            buffer.setLine(position.line(), updated);
            invalidateVisualWidthCache();
            return new Position(position.line(), start);
        }

        if (position.line() >= buffer.lineCount() - 1) {
            return new Position(position.line(), col);
        }

        String next = buffer.removeLine(position.line() + 1);
        buffer.setLine(position.line(), line + next);
        invalidateVisualWidthCache();
        return new Position(position.line(), col);
    }

    public String getTextRange(Position start, Position end) {
        Position a = clamp(start);
        Position b = clamp(end);
        if (comesAfter(a, b)) {
            Position t = a;
            a = b;
            b = t;
        }
        if (a.equals(b)) {
            return "";
        }
        if (a.line() == b.line()) {
            String line = buffer.getLine(a.line());
            return line.substring(a.col(), b.col());
        }

        StringBuilder out = new StringBuilder();
        out.append(buffer.getLine(a.line()).substring(a.col()));
        out.append('\n');
        for (int line = a.line() + 1; line < b.line(); line++) {
            out.append(buffer.getLine(line)).append('\n');
        }
        out.append(buffer.getLine(b.line()), 0, b.col());
        return out.toString();
    }

    public Position deleteRange(Position start, Position end) {
        Position a = clamp(start);
        Position b = clamp(end);
        if (comesAfter(a, b)) {
            Position t = a;
            a = b;
            b = t;
        }
        if (a.equals(b)) {
            return a;
        }

        if (a.line() == b.line()) {
            String line = buffer.getLine(a.line());
            buffer.setLine(a.line(), line.substring(0, a.col()) + line.substring(b.col()));
            invalidateVisualWidthCache();
            return a;
        }

        String head = buffer.getLine(a.line()).substring(0, a.col());
        String tail = buffer.getLine(b.line()).substring(b.col());
        buffer.setLine(a.line(), head + tail);
        for (int i = b.line(); i > a.line(); i--) {
            buffer.removeLine(i);
        }
        invalidateVisualWidthCache();
        return a;
    }

    public Position insertText(Position position, String text) {
        if (text == null || text.isEmpty()) {
            return clamp(position);
        }

        Position at = clamp(position);
        String normalized = text.replace("\r\n", "\n").replace('\r', '\n');
        String[] parts = normalized.split("\n", -1);
        String line = buffer.getLine(at.line());
        int col = Math.min(at.col(), line.length());
        String left = line.substring(0, col);
        String right = line.substring(col);

        if (parts.length == 1) {
            buffer.setLine(at.line(), left + parts[0] + right);
            invalidateVisualWidthCache();
            return new Position(at.line(), col + parts[0].length());
        }

        buffer.setLine(at.line(), left + parts[0]);
        for (int i = 1; i < parts.length - 1; i++) {
            buffer.insertLine(at.line() + i, parts[i]);
        }
        String last = parts[parts.length - 1];
        buffer.insertLine(at.line() + parts.length - 1, last + right);
        invalidateVisualWidthCache();
        return new Position(at.line() + parts.length - 1, last.length());
    }

    public Position clamp(Position requested) {
        int line = Math.max(0, Math.min(requested.line(), buffer.lineCount() - 1));
        int col = Math.max(0, Math.min(requested.col(), buffer.getLine(line).length()));
        return new Position(line, col);
    }

    public LoadMetadata load(Path path) throws IOException {
        byte[] data = Files.readAllBytes(path);
        DecodedContent decoded = decodeWithDetectedCharset(data);
        String lineSeparator = detectLineSeparator(decoded.text());
        List<String> lines = splitLines(decoded.text());
        clearDisplayMetricsCache();
        buffer.clear();
        if (lines.isEmpty()) {
            buffer.setLine(0, "");
            return new LoadMetadata(decoded.charset(), lineSeparator);
        }
        buffer.setLine(0, lines.get(0));
        for (int i = 1; i < lines.size(); i++) {
            buffer.insertLine(i, lines.get(i));
        }
        invalidateVisualWidthCache();
        return new LoadMetadata(decoded.charset(), lineSeparator);
    }

    public void save(Path path) throws IOException {
        save(path, StandardCharsets.UTF_8, EditorState.LINE_SEPARATOR_LF);
    }

    public void save(Path path, Charset charset) throws IOException {
        save(path, charset, EditorState.LINE_SEPARATOR_LF);
    }

    public void save(Path path, Charset charset, String lineSeparator) throws IOException {
        String normalizedSeparator = normalizeLineSeparator(lineSeparator);
        String content = String.join(normalizedSeparator, snapshotLines());
        Path requestedTarget = path.toAbsolutePath().normalize();
        Path target = resolveSaveTarget(requestedTarget);
        boolean targetExists = Files.exists(target);
        Set<PosixFilePermission> targetPermissions = readPosixPermissions(target, targetExists);
        Path parent = target.getParent();
        Path tempDir = parent != null ? parent : Path.of(".").toAbsolutePath().normalize();
        String fileName = target.getFileName() == null ? "tuink" : target.getFileName().toString();
        String prefix = "." + sanitizePrefix(fileName) + ".";
        if (prefix.length() < 3) {
            prefix = ".tuink.";
        }

        Path tempFile = createSaveTempFile(tempDir, prefix, targetExists);
        try {
            Files.writeString(tempFile, content, charset);
            if (targetPermissions != null) {
                Files.setPosixFilePermissions(tempFile, targetPermissions);
            }
            try {
                Files.move(
                    tempFile,
                    target,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE
                );
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tempFile, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(tempFile);
        }
    }

    private static Path resolveSaveTarget(Path requestedTarget) throws IOException {
        if (Files.isSymbolicLink(requestedTarget)) {
            return requestedTarget.toRealPath();
        }
        return requestedTarget;
    }

    private static Set<PosixFilePermission> readPosixPermissions(Path target, boolean targetExists)
        throws IOException {
        if (!targetExists) {
            return null;
        }
        try {
            return Files.getPosixFilePermissions(target);
        } catch (UnsupportedOperationException ignored) {
            return null;
        }
    }

    private static Path createSaveTempFile(Path tempDir, String prefix, boolean targetExists)
        throws IOException {
        if (targetExists) {
            return Files.createTempFile(tempDir, prefix, ".tmp");
        }
        try {
            return Files.createTempFile(
                tempDir,
                prefix,
                ".tmp",
                PosixFilePermissions.asFileAttribute(NEW_FILE_PERMISSIONS)
            );
        } catch (UnsupportedOperationException ignored) {
            return Files.createTempFile(tempDir, prefix, ".tmp");
        }
    }

    public List<String> snapshotLines() {
        return java.util.stream.IntStream.range(0, lineCount())
            .mapToObj(this::getLine)
            .toList();
    }

    public int maxLineLength() {
        int max = 0;
        for (int i = 0; i < lineCount(); i++) {
            max = Math.max(max, getLine(i).length());
        }
        return max;
    }

    public int maxVisualLineWidth(int tabWidth) {
        int safeTabWidth = Math.max(1, tabWidth);
        if (!maxVisualWidthDirty && cachedVisualWidthTabWidth == safeTabWidth) {
            return cachedMaxVisualLineWidth;
        }
        int max = 0;
        for (int i = 0; i < lineCount(); i++) {
            max = Math.max(max, displayMetrics(i, safeTabWidth).visualWidth());
        }
        cachedVisualWidthTabWidth = safeTabWidth;
        cachedMaxVisualLineWidth = max;
        maxVisualWidthDirty = false;
        return max;
    }

    public long revision() {
        return revision;
    }

    public DisplayText.Metrics displayMetrics(int line, int tabWidth) {
        if (lineCount() <= 0) {
            return DisplayText.metrics("", tabWidth);
        }
        int safeLine = Math.max(0, Math.min(line, lineCount() - 1));
        int safeTabWidth = Math.max(1, tabWidth);
        String text = getLine(safeLine);
        DisplayMetricsEntry cached = displayMetricsCache.get(safeLine);
        if (cached != null && cached.matches(text, safeTabWidth)) {
            return cached.metrics();
        }
        if (cached != null) {
            displayMetricsCache.remove(safeLine);
            displayMetricsWeight -= cached.weight();
        }

        DisplayText.Metrics metrics = DisplayText.metrics(text, safeTabWidth);
        DisplayMetricsEntry computed = new DisplayMetricsEntry(text, safeTabWidth, metrics);
        displayMetricsCache.put(safeLine, computed);
        displayMetricsWeight += computed.weight();
        trimDisplayMetricsCache();
        return metrics;
    }

    public List<Integer> wrapStarts(int line, int width, boolean wrapByWords, int tabWidth) {
        if (lineCount() <= 0) {
            return List.of(0);
        }
        int safeLine = Math.max(0, Math.min(line, lineCount() - 1));
        WrapStartsKey key = new WrapStartsKey(safeLine, Math.max(0, width), wrapByWords, Math.max(1, tabWidth));
        List<Integer> cached = wrapStartsCache.get(key);
        if (cached != null) {
            return cached;
        }
        List<Integer> computed = ViewportMetrics.wrapStarts(
            displayMetrics(safeLine, key.tabWidth()),
            key.width(),
            key.wrapByWords()
        );
        wrapStartsCache.put(key, computed);
        return computed;
    }

    public int wrappedRowsBefore(int lineIndexExclusive, int width, boolean wrapByWords, int tabWidth) {
        int[] prefix = wrapPrefix(width, wrapByWords, tabWidth);
        int limit = Math.max(0, Math.min(lineIndexExclusive, lineCount()));
        return prefix[limit];
    }

    public int totalWrappedRows(int width, boolean wrapByWords, int tabWidth) {
        int[] prefix = wrapPrefix(width, wrapByWords, tabWidth);
        return prefix[Math.max(0, lineCount())];
    }

    public int lineIndexFromWrappedOffset(int wrappedOffset, int width, boolean wrapByWords, int tabWidth) {
        int[] prefix = wrapPrefix(width, wrapByWords, tabWidth);
        int total = prefix[Math.max(0, lineCount())];
        int target = Math.max(0, Math.min(wrappedOffset, Math.max(0, total - 1)));
        int lo = 0;
        int hi = Math.max(0, lineCount() - 1);
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            int start = prefix[mid];
            int end = prefix[mid + 1];
            if (target < start) {
                hi = mid - 1;
            } else if (target >= end) {
                lo = mid + 1;
            } else {
                return mid;
            }
        }
        return Math.max(0, lineCount() - 1);
    }

    public void replaceLines(List<String> lines) {
        clearDisplayMetricsCache();
        buffer.clear();
        if (lines == null || lines.isEmpty()) {
            buffer.setLine(0, "");
            invalidateVisualWidthCache();
            return;
        }
        buffer.setLine(0, lines.get(0));
        for (int i = 1; i < lines.size(); i++) {
            buffer.insertLine(i, lines.get(i));
        }
        invalidateVisualWidthCache();
    }

    private void invalidateVisualWidthCache() {
        maxVisualWidthDirty = true;
        wrapStartsCache.clear();
        wrapPrefixCache.clear();
        revision++;
    }

    private void trimDisplayMetricsCache() {
        var iterator = displayMetricsCache.entrySet().iterator();
        while (iterator.hasNext()
            && (displayMetricsCache.size() > MAX_DISPLAY_METRICS_LINES
                || (displayMetricsWeight > MAX_DISPLAY_METRICS_WEIGHT
                    && displayMetricsCache.size() > 1))) {
            DisplayMetricsEntry removed = iterator.next().getValue();
            displayMetricsWeight -= removed.weight();
            iterator.remove();
        }
    }

    private void clearDisplayMetricsCache() {
        displayMetricsCache.clear();
        displayMetricsWeight = 0;
    }

    private record DisplayMetricsEntry(String lineText, int tabWidth, DisplayText.Metrics metrics) {
        private boolean matches(String text, int width) {
            return tabWidth == width && lineText.equals(text);
        }

        private int weight() {
            return Math.max(1, metrics.storageWeight());
        }
    }

    private record WrapStartsKey(int line, int width, boolean wrapByWords, int tabWidth) {
    }

    private int[] wrapPrefix(int width, boolean wrapByWords, int tabWidth) {
        WrapLayoutKey key = new WrapLayoutKey(Math.max(0, width), wrapByWords, Math.max(1, tabWidth));
        int[] cached = wrapPrefixCache.get(key);
        if (cached != null) {
            return cached;
        }
        int count = lineCount();
        int[] prefix = new int[count + 1];
        for (int i = 0; i < count; i++) {
            prefix[i + 1] = prefix[i] + wrapStarts(i, key.width(), key.wrapByWords(), key.tabWidth()).size();
        }
        wrapPrefixCache.put(key, prefix);
        return prefix;
    }

    private record WrapLayoutKey(int width, boolean wrapByWords, int tabWidth) {
    }

    private DecodedContent decodeWithDetectedCharset(byte[] data) {
        if (hasUtf8Bom(data)) {
            return new DecodedContent(StandardCharsets.UTF_8, new String(data, 3, data.length - 3, StandardCharsets.UTF_8));
        }
        if (hasUtf16LeBom(data)) {
            return new DecodedContent(StandardCharsets.UTF_16LE, new String(data, 2, data.length - 2, StandardCharsets.UTF_16LE));
        }
        if (hasUtf16BeBom(data)) {
            return new DecodedContent(StandardCharsets.UTF_16BE, new String(data, 2, data.length - 2, StandardCharsets.UTF_16BE));
        }
        if (isValidUtf8(data)) {
            return new DecodedContent(StandardCharsets.UTF_8, new String(data, StandardCharsets.UTF_8));
        }
        return new DecodedContent(FALLBACK_CHARSET, new String(data, FALLBACK_CHARSET));
    }

    private static List<String> splitLines(String content) {
        String normalized = content.replace("\r\n", "\n").replace('\r', '\n');
        List<String> parts = List.of(normalized.split("\n", -1));
        if (!parts.isEmpty() && parts.get(parts.size() - 1).isEmpty()) {
            return parts.subList(0, parts.size() - 1);
        }
        return parts;
    }

    private static String detectLineSeparator(String text) {
        if (text.contains("\r\n")) {
            return EditorState.LINE_SEPARATOR_CRLF;
        }
        if (text.indexOf('\r') >= 0) {
            return EditorState.LINE_SEPARATOR_CR;
        }
        return EditorState.LINE_SEPARATOR_LF;
    }

    private static String normalizeLineSeparator(String lineSeparator) {
        if (EditorState.LINE_SEPARATOR_CRLF.equals(lineSeparator)) {
            return EditorState.LINE_SEPARATOR_CRLF;
        }
        if (EditorState.LINE_SEPARATOR_CR.equals(lineSeparator)) {
            return EditorState.LINE_SEPARATOR_CR;
        }
        return EditorState.LINE_SEPARATOR_LF;
    }

    private static boolean isValidUtf8(byte[] data) {
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            decoder.decode(ByteBuffer.wrap(data));
            return true;
        } catch (CharacterCodingException e) {
            return false;
        }
    }

    private static boolean hasUtf8Bom(byte[] data) {
        return data.length >= 3
            && (data[0] & 0xFF) == 0xEF
            && (data[1] & 0xFF) == 0xBB
            && (data[2] & 0xFF) == 0xBF;
    }

    private static boolean hasUtf16LeBom(byte[] data) {
        return data.length >= 2
            && (data[0] & 0xFF) == 0xFF
            && (data[1] & 0xFF) == 0xFE;
    }

    private static boolean hasUtf16BeBom(byte[] data) {
        return data.length >= 2
            && (data[0] & 0xFF) == 0xFE
            && (data[1] & 0xFF) == 0xFF;
    }

    private record DecodedContent(Charset charset, String text) {
    }

    public record LoadMetadata(Charset charset, String lineSeparator) {
    }

    private static boolean comesAfter(Position a, Position b) {
        return a.line() > b.line() || (a.line() == b.line() && a.col() > b.col());
    }

    private static String sanitizePrefix(String name) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < name.length(); i++) {
            char ch = name.charAt(i);
            if (Character.isLetterOrDigit(ch) || ch == '_' || ch == '-') {
                out.append(ch);
            }
        }
        if (out.length() < 3) {
            out.append("tuink");
        }
        return out.substring(0, Math.max(3, Math.min(out.length(), 32)));
    }
}
