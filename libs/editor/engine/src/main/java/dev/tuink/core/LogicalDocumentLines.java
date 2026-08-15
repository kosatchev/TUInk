package dev.tuink.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Shared validation for the line-oriented core document representation. */
final class LogicalDocumentLines {
    private LogicalDocumentLines() {
    }

    static List<String> copyOfOrEmptyDocument(List<String> lines) {
        if (lines == null || lines.isEmpty()) {
            return List.of("");
        }
        ArrayList<String> checked = new ArrayList<>(lines.size());
        for (String line : lines) {
            String value = Objects.requireNonNull(line, "Document lines must not contain null");
            if (value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0) {
                throw new IllegalArgumentException(
                    "Logical document lines must not contain line separators"
                );
            }
            requireWellFormedUtf16(value);
            checked.add(value);
        }
        return List.copyOf(checked);
    }

    static void requireWellFormedUtf16(String text) {
        Objects.requireNonNull(text, "text");
        for (int index = 0; index < text.length(); index++) {
            char current = text.charAt(index);
            if (Character.isHighSurrogate(current)) {
                if (index + 1 >= text.length() || !Character.isLowSurrogate(text.charAt(index + 1))) {
                    throw new IllegalArgumentException("Text contains an unpaired UTF-16 surrogate");
                }
                index++;
            } else if (Character.isLowSurrogate(current)) {
                throw new IllegalArgumentException("Text contains an unpaired UTF-16 surrogate");
            }
        }
    }
}
