package dev.tuink.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;

class DocumentEditBudgetTest {
    @Test
    void copiedTextHasAnInclusivePeakBudgetAndUsesUtf16WorstCase() {
        long peak = 128 * 1024L + 16 + 256 + 8 * 100;
        DocumentEditBudget exact = new DocumentEditBudget(1, peak);
        exact.append(100, true);
        exact.finishLine(100, true);
        assertThrows(DocumentEditLimitException.class,
            () -> new DocumentEditBudget(1, peak - 1).append(100, true));
    }

    @Test
    void appendingToAReusedWholeLineChargesItsEntireCopiedPrefix() {
        String original = "prefix".repeat(100);
        long limit = 128 * 1024L + 16 + 256 + 8 * original.length();
        ReplacementBuilder counter = ReplacementBuilder.counting(new DocumentEditBudget(1, limit));
        counter.append(original, 0, original.length());
        assertThrows(DocumentEditLimitException.class, () -> counter.append("x", 0, 1));
    }

    @Test
    void lineArraysAreChargedEvenWhenReplacementLinesAreEmpty() {
        ReplacementBuilder counter = ReplacementBuilder.counting(new DocumentEditBudget(1, 1024 * 1024));
        ReplacementText fanOut = new ReplacementText("\n".repeat(100_000));
        assertThrows(DocumentEditLimitException.class, () -> counter.appendReplacement(fanOut, "\n"));
    }

    @Test
    void byteArrayLimitsAndLongArithmeticCannotWrapIntoAdmission() {
        DocumentEditBudget unlimited = new DocumentEditBudget(1, Long.MAX_VALUE);
        assertThrows(DocumentEditLimitException.class,
            () -> unlimited.append(DocumentEditBudget.MAX_LINE_CODE_UNITS + 1, true));
        assertThrows(DocumentEditLimitException.class,
            () -> unlimited.finishLines(0, DocumentEditBudget.MAX_LINES + 1, 0));
        assertThrows(DocumentEditLimitException.class, () -> unlimited.finishLines(Long.MAX_VALUE, 1, 0));
        assertThrows(DocumentEditLimitException.class, () -> DocumentEditBudget.add(Long.MAX_VALUE, 1));
    }

    @Test
    void localReplacementChargesChangedTextAndWholeDocumentReferenceArrays() {
        long peak = 128 * 1024L + 8 * 100 + 256 * 2 + 16 * 1000 + 32 * 1001;
        new DocumentEditBudget(1000, peak).requireLocalReplacement(100, 1001, 2, 75);
        assertThrows(DocumentEditLimitException.class,
            () -> new DocumentEditBudget(1000, peak - 1).requireLocalReplacement(100, 1001, 2, 75));
        assertThrows(DocumentEditLimitException.class,
            () -> new DocumentEditBudget(1, Long.MAX_VALUE)
                .requireLocalReplacement(0, DocumentEditBudget.MAX_LINES + 1, 1, 0));
        assertThrows(DocumentEditLimitException.class,
            () -> new DocumentEditBudget(1, Long.MAX_VALUE)
                .requireLocalReplacement(0, 1, 1, DocumentEditBudget.MAX_LINE_CODE_UNITS + 1));
    }

    @Test
    void cachedMultilineShapeAndMaterializationAgreeOnNormalizedOutput() {
        ReplacementBuilder counter = ReplacementBuilder.counting(new DocumentEditBudget(1, 1024 * 1024));
        ReplacementText replacement = new ReplacementText("\r\nĀ\r\n\rX");
        ReplacementBuilder actual = counter.materializer();
        for (ReplacementBuilder output : List.of(counter, actual)) {
            output.append("before", 0, 6);
            output.appendReplacement(replacement, "\n");
            output.append("tail", 0, 4);
            assertEquals(new Position(3, 5), output.position());
            output.finishLine();
        }
        assertEquals(List.of("before", "Ā", "", "Xtail"), actual.lines);
        assertEquals(List.of("\n", "\n", "\n"), actual.separators);
    }
}
