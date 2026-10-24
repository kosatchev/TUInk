package dev.tuiframe.text;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class TerminalTextAllocationTest {
    @Test
    void predictsConstantSparseAndDenseIndicesUsingTheSameGraphemeSegmentationAsMetrics() {
        assertEquals(512, TerminalText.estimatedMetricsBytes("a".repeat(10_000), 4));
        assertEquals(512 + 13 + 4, TerminalText.estimatedMetricsBytes("a".repeat(10_000) + "\t", 4));
        assertEquals(512 + 10L * 1_000 + 8, TerminalText.estimatedMetricsBytes("界".repeat(1_000), 4));
        assertEquals(512 + 13, TerminalText.estimatedMetricsBytes("a".repeat(10_000) + "👩‍💻", 4));
    }
}
