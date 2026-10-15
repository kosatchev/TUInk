package dev.tuink.unicode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class GraphemeTextPropertyTest {
    private static final String[] TOKENS = {
        "a", "Z", "0", " ", "\t", "\r", "\n", "e\u0301", "\u0301", "中", "😀",
        "👨‍👩‍👧‍👦", "🇷🇺", "👍🏽", "1\uFE0F\u20E3", "\u1100\u1161",
        "\u200D", "\uD83D", "\uDC4D", "\u0600", "\u0915\u094D\u0937", "\u2003", "Ā"
    };

    @Test
    void deterministicRandomLayoutsObeyBoundaryAndMetadataInvariants() {
        Random random = new Random(0x4752415048454D45L);

        for (int sample = 0; sample < 500; sample++) {
            // Long arithmetic runs exercise the sparse representation; clusters
            // without a prefix also exercise the dense representation.
            String text = (sample % 4 == 0 ? "a".repeat(100) : "") + randomText(random);
            String message = context(sample, text);
            GraphemeText layout = GraphemeText.of(text);
            List<Integer> reference = referenceBoundaries(text);
            assertEquals(reference.size() - 1, layout.graphemeCount(), message);
            StringBuilder reconstructed = new StringBuilder(text.length());
            Set<Integer> boundaries = new HashSet<>();
            boundaries.add(0);
            int previousEnd = 0;

            for (int index = 0; index < layout.graphemeCount(); index++) {
                int start = layout.start(index);
                int end = layout.end(index);
                assertEquals(reference.get(index), start, message);
                assertEquals(reference.get(index + 1), end, message);
                assertEquals(previousEnd, start, message);
                assertTrue(end > start, message);
                String cluster = text.substring(start, end);
                reconstructed.append(cluster);
                assertEquals("\t".equals(cluster), layout.isTab(index), message);
                assertEquals(isWhitespace(cluster), layout.isWhitespace(index), message);
                boundaries.add(end);
                previousEnd = end;
            }

            assertEquals(text, reconstructed.toString(), message);
            assertEquals(text.length(), previousEnd, message);
            assertEquals(isSimpleAscii(text), layout.simpleAscii(), message);
            assertTrue(layout.storageWeight() >= 1, message);
            assertTrue(layout.storageWeight() <= layout.graphemeCount() + 1, message);

            for (int offset = -2; offset <= text.length() + 2; offset++) {
                int safe = Math.max(0, Math.min(offset, text.length()));
                int floor = layout.floorBoundary(offset);
                int ceil = layout.ceilBoundary(offset);
                int previous = layout.previousBoundary(offset);
                int next = layout.nextBoundary(offset);
                int found = Collections.binarySearch(reference, safe);
                int floorIndex = found >= 0 ? found : -found - 2;
                int ceilIndex = found >= 0 ? found : -found - 1;

                assertEquals(reference.get(floorIndex), floor, message);
                assertEquals(reference.get(ceilIndex), ceil, message);
                assertEquals(ceil, GraphemeText.ceilBoundary(text, offset), message);
                assertEquals(reference.get(Math.max(0, found >= 0 ? found - 1 : floorIndex)), previous, message);
                assertEquals(reference.get(Math.min(reference.size() - 1,
                    found >= 0 ? found + 1 : ceilIndex)), next, message);

                assertTrue(boundaries.contains(floor), message);
                assertTrue(boundaries.contains(ceil), message);
                assertTrue(boundaries.contains(previous), message);
                assertTrue(boundaries.contains(next), message);
                assertTrue(floor <= safe && safe <= ceil, message);
                assertTrue(previous <= safe && safe <= next, message);
                assertEquals(floor, layout.floorBoundary(floor), message);
                assertEquals(ceil, layout.ceilBoundary(ceil), message);
                assertEquals(
                    offset >= 0 && offset <= text.length() && boundaries.contains(offset),
                    layout.isBoundary(offset),
                    message
                );
                if (safe > 0) {
                    assertTrue(previous < safe, message);
                }
                if (safe < text.length()) {
                    assertTrue(next > safe, message);
                }
            }

            for (int offset = 0; offset + 1 < text.length(); offset++) {
                if (Character.isHighSurrogate(text.charAt(offset))
                    && Character.isLowSurrogate(text.charAt(offset + 1))) {
                    assertFalse(layout.isBoundary(offset + 1), message);
                }
            }
        }
    }

    private static List<Integer> referenceBoundaries(String text) {
        var matcher = Pattern.compile("\\X").matcher(text);
        List<Integer> boundaries = new ArrayList<>();
        boundaries.add(0);
        while (matcher.find()) {
            boundaries.add(matcher.end());
        }
        return boundaries;
    }

    private static String randomText(Random random) {
        int tokenCount = random.nextInt(32);
        StringBuilder text = new StringBuilder(tokenCount * 2);
        for (int index = 0; index < tokenCount; index++) {
            text.append(TOKENS[random.nextInt(TOKENS.length)]);
        }
        return text.toString();
    }

    private static boolean isWhitespace(String text) {
        if (text.isEmpty()) {
            return false;
        }
        for (int index = 0; index < text.length();) {
            int codePoint = text.codePointAt(index);
            if (!Character.isWhitespace(codePoint)) {
                return false;
            }
            index += Character.charCount(codePoint);
        }
        return true;
    }

    private static boolean isSimpleAscii(String text) {
        for (int index = 0; index < text.length(); index++) {
            char ch = text.charAt(index);
            if (ch > 0x7F || ch == '\r' || ch == '\n') {
                return false;
            }
        }
        return true;
    }

    private static String context(int sample, String text) {
        return "sample=" + sample + ", text=" + escape(text);
    }

    private static String escape(String text) {
        StringBuilder escaped = new StringBuilder();
        text.codePoints().forEach(codePoint -> {
            if (codePoint >= 0x20 && codePoint <= 0x7E) {
                escaped.appendCodePoint(codePoint);
            } else if (codePoint <= 0xFFFF) {
                escaped.append(String.format("\\u%04X", codePoint));
            } else {
                escaped.append(String.format("\\u{%X}", codePoint));
            }
        });
        return escaped.toString();
    }
}
