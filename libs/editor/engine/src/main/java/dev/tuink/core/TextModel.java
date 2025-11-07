package dev.tuink.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Editing operations and persistence over a {@link TextBuffer}.
 */
public class TextModel {
    private final TextBuffer buffer;

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

    public Position clamp(Position requested) {
        int line = Math.max(0, Math.min(requested.line(), buffer.lineCount() - 1));
        int col = Math.max(0, Math.min(requested.col(), buffer.getLine(line).length()));
        return new Position(line, col);
    }

    public void load(Path path) throws IOException {
        List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
        buffer.clear();
        if (lines.isEmpty()) {
            buffer.setLine(0, "");
            return;
        }
        buffer.setLine(0, lines.get(0));
        for (int i = 1; i < lines.size(); i++) {
            buffer.insertLine(i, lines.get(i));
        }
    }

    public void save(Path path) throws IOException {
        String content = String.join("\n", snapshotLines());
        Files.writeString(path, content, StandardCharsets.UTF_8);
    }

    private List<String> snapshotLines() {
        return java.util.stream.IntStream.range(0, lineCount())
            .mapToObj(this::getLine)
            .toList();
    }
}
