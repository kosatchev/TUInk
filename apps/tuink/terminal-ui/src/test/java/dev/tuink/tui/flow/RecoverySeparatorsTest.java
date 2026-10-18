package dev.tuink.tui.flow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class RecoverySeparatorsTest {
    @Test
    void packedSeparatorsRetainMixedOrderingAndEveryPartialByte() throws Exception {
        for (int count = 0; count < 40; count++) {
            List<String> separators = IntStream.range(0, count)
                .mapToObj(index -> switch (index % 3) { case 0 -> "\n"; case 1 -> "\r\n"; default -> "\r"; })
                .toList();
            String encoded = RecoverySeparators.encode(separators);
            var parsed = RecoverySeparators.parse(encoded, false);
            assertEquals(count, parsed.count());
            assertEquals(separators, parsed.materialize());
        }
    }

    @Test
    void packingAlsoBoundsAlternatingSeparatorsInsteadOfDependingOnLongRuns() throws Exception {
        List<String> separators = IntStream.range(0, 100_000)
            .mapToObj(index -> index % 2 == 0 ? "\r\n" : "\r").toList();
        String encoded = RecoverySeparators.encode(separators);
        assertTrue(encoded.length() < 34_000);
        assertEquals(separators, RecoverySeparators.parse(encoded, false).materialize());
    }

    @Test
    void legacyCsvRemainsReadableButMalformedOrNoncanonicalValuesAreRejected() throws Exception {
        assertEquals(List.of("\r\n", "\n", "\r"), RecoverySeparators.parse("CRLF,LF,CR", true).materialize());
        assertEquals(List.of(), RecoverySeparators.parse("", true).materialize());
        for (String malformed : List.of(",LF", "LF,", "LF,,CR", "lf", " LF", "packed2:0:")) {
            assertThrows(IOException.class, () -> RecoverySeparators.parse(malformed, true), malformed);
        }
        for (String malformed : List.of("", "LF", "packed2:01:AA==", "packed2:-1:", "packed2:1:",
            "packed2:0:AA==", "packed2:1:Aw==", "packed2:1:BA==", "packed2:1:AB==", "packed2:1:AA",
            "packed2:2147483647:", "packed2:67108865:", "packed2:4:AAAA")) {
            assertThrows(IOException.class, () -> RecoverySeparators.parse(malformed, false), malformed);
        }
    }

    @Test
    void expandedCountsAndOversizedPackedValuesAreRejectedBeforeAllocatingTheirArrays() {
        assertThrows(IOException.class, () -> RecoverySeparators.parse("packed2:67108864:", false));
        assertThrows(IOException.class,
            () -> RecoverySeparators.encode(Collections.nCopies(RecoveryLimits.MAX_CODE_UNITS, "\n")));
        assertThrows(IOException.class,
            () -> RecoveryLimits.requireDocument(Collections.nCopies(RecoveryLimits.MAX_CODE_UNITS + 2, "")));
    }
}
