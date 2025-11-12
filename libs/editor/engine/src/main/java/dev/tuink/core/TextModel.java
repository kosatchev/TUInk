package dev.tuink.core;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Editing operations and persistence over a {@link TextBuffer}.
 */
public class TextModel {
    private final TextBuffer buffer;
    private static final Charset FALLBACK_CHARSET = Charset.forName("windows-1251");

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
        return new Position(position.line(), col + 1);
    }

    public Position insertNewline(Position position) {
        String line = buffer.getLine(position.line());
        int col = Math.min(position.col(), line.length());
        String left = line.substring(0, col);
        String right = line.substring(col);
        buffer.setLine(position.line(), left);
        buffer.insertLine(position.line() + 1, right);
        return new Position(position.line() + 1, 0);
    }

    public Position deleteBackward(Position position) {
        if (position.col() > 0) {
            String line = buffer.getLine(position.line());
            int col = Math.min(position.col(), line.length());
            String updated = line.substring(0, col - 1) + line.substring(col);
            buffer.setLine(position.line(), updated);
            return new Position(position.line(), col - 1);
        }

        if (position.line() == 0) {
            return position;
        }

        String current = buffer.removeLine(position.line());
        int prevLineIndex = position.line() - 1;
        String prev = buffer.getLine(prevLineIndex);
        buffer.setLine(prevLineIndex, prev + current);
        return new Position(prevLineIndex, prev.length());
    }

    public Position deleteForward(Position position) {
        String line = buffer.getLine(position.line());
        int col = Math.min(position.col(), line.length());

        if (col < line.length()) {
            String updated = line.substring(0, col) + line.substring(col + 1);
            buffer.setLine(position.line(), updated);
            return new Position(position.line(), col);
        }

        if (position.line() >= buffer.lineCount() - 1) {
            return new Position(position.line(), col);
        }

        String next = buffer.removeLine(position.line() + 1);
        buffer.setLine(position.line(), line + next);
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
            return a;
        }

        String head = buffer.getLine(a.line()).substring(0, a.col());
        String tail = buffer.getLine(b.line()).substring(b.col());
        buffer.setLine(a.line(), head + tail);
        for (int i = b.line(); i > a.line(); i--) {
            buffer.removeLine(i);
        }
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
            return new Position(at.line(), col + parts[0].length());
        }

        buffer.setLine(at.line(), left + parts[0]);
        for (int i = 1; i < parts.length - 1; i++) {
            buffer.insertLine(at.line() + i, parts[i]);
        }
        String last = parts[parts.length - 1];
        buffer.insertLine(at.line() + parts.length - 1, last + right);
        return new Position(at.line() + parts.length - 1, last.length());
    }

    public Position clamp(Position requested) {
        int line = Math.max(0, Math.min(requested.line(), buffer.lineCount() - 1));
        int col = Math.max(0, Math.min(requested.col(), buffer.getLine(line).length()));
        return new Position(line, col);
    }

    public Charset load(Path path) throws IOException {
        byte[] data = Files.readAllBytes(path);
        DecodedContent decoded = decodeWithDetectedCharset(data);
        List<String> lines = splitLines(decoded.text());
        buffer.clear();
        if (lines.isEmpty()) {
            buffer.setLine(0, "");
            return decoded.charset();
        }
        buffer.setLine(0, lines.get(0));
        for (int i = 1; i < lines.size(); i++) {
            buffer.insertLine(i, lines.get(i));
        }
        return decoded.charset();
    }

    public void save(Path path) throws IOException {
        save(path, StandardCharsets.UTF_8);
    }

    public void save(Path path, Charset charset) throws IOException {
        String content = String.join("\n", snapshotLines());
        Files.writeString(path, content, charset);
    }

    public List<String> snapshotLines() {
        return java.util.stream.IntStream.range(0, lineCount())
            .mapToObj(this::getLine)
            .toList();
    }

    public void replaceLines(List<String> lines) {
        buffer.clear();
        if (lines == null || lines.isEmpty()) {
            buffer.setLine(0, "");
            return;
        }
        buffer.setLine(0, lines.get(0));
        for (int i = 1; i < lines.size(); i++) {
            buffer.insertLine(i, lines.get(i));
        }
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

    private static boolean comesAfter(Position a, Position b) {
        return a.line() > b.line() || (a.line() == b.line() && a.col() > b.col());
    }
}
