package dev.tuink.tui;

import static org.junit.jupiter.api.Assertions.*;

import dev.tuink.editor.document.DocumentId;
import dev.tuink.editor.document.DocumentSnapshot;
import dev.tuink.editor.storage.FrozenDocumentSource;
import dev.tuink.editor.document.ImmutableText;
import dev.tuink.editor.storage.DocumentFileMetadata;
import dev.tuink.editor.storage.ResourceBinding;
import dev.tuink.editor.highlight.SemanticStyleKey;
import dev.tuink.editor.highlight.TokenSpan;
import dev.tuink.render.markdown.MarkdownLayout;
import dev.tuink.markdown.MarkdownDocument;
import dev.tuink.markdown.TextProjection;
import java.util.List;
import org.junit.jupiter.api.Test;

class MarkdownPreviewBudgetTest {
    @Test void syntaxRetentionIsChargedToTheSameLayoutBudgetAndReleased() {
        var document = new MarkdownDocument(new DocumentId("code"), 0, List.of(), List.of(),
            new TextProjection("return", List.of()));
        var plain = new MarkdownLayout.Line("return", List.of(), "return", null, "code");
        var styled = new MarkdownLayout.Line("return", List.of(), "return", null, "code",
            List.of(new TokenSpan(0, 6, SemanticStyleKey.KEYWORD)));
        long plainBytes;
        try (var budget = new MarkdownPreviewBudget(MarkdownPreviewBudget.Limits.DEFAULT)) {
            try (var lease = budget.result(new MarkdownLayout.Result(document, List.of(plain), 20, 6))) {
                plainBytes = budget.usedBytes(MarkdownPreviewBudget.Kind.LAYOUT);
            }
            assertEquals(0, budget.usedBytes());
            try (var lease = budget.result(new MarkdownLayout.Result(document, List.of(styled), 20, 6))) {
                assertTrue(budget.usedBytes(MarkdownPreviewBudget.Kind.LAYOUT) > plainBytes);
            }
            assertEquals(0, budget.usedBytes());
        }
        try (var budget = new MarkdownPreviewBudget(new MarkdownPreviewBudget.Limits(
                1_000_000, 1_000_000, 1_000_000, plainBytes))) {
            try (var lease = budget.result(new MarkdownLayout.Result(document, List.of(plain), 20, 6))) {
                assertThrows(MarkdownPreviewBudget.Refused.class, () ->
                    budget.result(new MarkdownLayout.Result(document, List.of(styled), 20, 6)));
                assertEquals(plainBytes, budget.usedBytes(MarkdownPreviewBudget.Kind.LAYOUT));
            }
            assertEquals(0, budget.usedBytes());
        }
    }

    @Test void renderAdmissionSharesTheExistingWindowAndLayoutLimits() {
        var budget = new MarkdownPreviewBudget(new MarkdownPreviewBudget.Limits(1200, 1200, 1200, 1200));
        var retained = budget.source(source("a".repeat(20)));
        long sourceBytes = budget.usedBytes();
        var render = budget.renderMemoryBudget();
        assertNull(render.tryReserve(1200));
        assertEquals(sourceBytes, budget.usedBytes());
        var frame = render.tryReserve(600);
        assertNotNull(frame);
        assertEquals(600, budget.usedBytes(MarkdownPreviewBudget.Kind.LAYOUT));
        assertEquals(sourceBytes + 600, budget.usedBytes());
        retained.close(); budget.close();
        assertNull(render.tryReserve(1));
        var later = frame.retain();
        frame.close();
        assertEquals(600, budget.usedBytes());
        later.close();
        assertEquals(0, budget.usedBytes());
    }

    @Test void workerTextAndViewEnvelopeShareTheSameRawInputChargesWithoutInventingMetadata() {
        var input = source("a".repeat(80));
        try (var budget = new MarkdownPreviewBudget(MarkdownPreviewBudget.Limits.DEFAULT)) {
            var view = budget.source(input); long full = budget.usedBytes();
            var worker = budget.sourceText(input.text());
            assertEquals(full, budget.usedBytes());
            view.close();
            assertTrue(budget.usedBytes() > 0 && budget.usedBytes() < full,
                "Only the real envelope metadata can retire while a worker still owns its text");
            worker.close(); assertEquals(0, budget.usedBytes());
        }
    }
    @Test void sharedImmutableLinesAreChargedOnceAcrossSnapshotsAndLeasesAreReleased() {
        var line = ImmutableText.of("x".repeat(400)).replace(200, 201, "y");
        assertNull(line.cachedString());
        var metadata = DocumentFileMetadata.newDocument(2);
        var a = new FrozenDocumentSource(DocumentSnapshot.fromImmutableLines(new DocumentId("a"), 0, List.of(line, line)), metadata, ResourceBinding.unbound());
        var b = new FrozenDocumentSource(DocumentSnapshot.fromImmutableLines(new DocumentId("b"), 0, List.of(line, line)), metadata, ResourceBinding.unbound());
        try (var budget = new MarkdownPreviewBudget(MarkdownPreviewBudget.Limits.DEFAULT)) {
            var first = budget.source(a); long once = budget.usedBytes();
            var second = budget.source(b);
            assertTrue(budget.usedBytes() - once < 200, "Only the second snapshot's reference table is new");
            first.close(); assertEquals(once, budget.usedBytes());
            second.close(); assertEquals(0, budget.usedBytes());
        }
        assertNull(line.cachedString(), "Accounting must not materialize persistent text into String");
    }

    @Test void failedAdmissionDoesNotEvictOrAlterAlreadyRetainedSnapshots() {
        var limits = new MarkdownPreviewBudget.Limits(2048, 1024, 2048, 2048);
        var small = source("a".repeat(80)); var large = source("b".repeat(1000));
        try (var budget = new MarkdownPreviewBudget(limits); var retained = budget.source(small)) {
            long before = budget.usedBytes();
            assertThrows(MarkdownPreviewBudget.Refused.class, () -> budget.source(large));
            assertEquals(before, budget.usedBytes());
            assertEquals("a".repeat(80), small.text().line(0));
        }
    }
    @Test void replacingAReceiptFormatNeedsOnlyTheFinalRetainedFootprint() {
        var initial = source("a".repeat(80));
        var format = initial.metadata().canonicalized(java.nio.charset.StandardCharsets.UTF_16LE, "\r\n", 1);
        var next = new FrozenDocumentSource(initial.text(), format, initial.binding());
        try (var budget = new MarkdownPreviewBudget(new MarkdownPreviewBudget.Limits(700, 700, 1000, 1000))) {
            var old = budget.source(initial); long before = budget.usedBytes();
            assertThrows(MarkdownPreviewBudget.Refused.class, () -> budget.source(next));
            var replaced = budget.replaceSource(old, next);
            assertEquals(before, budget.usedBytes());
            old.close(); assertEquals(before, budget.usedBytes());
            replaced.close(); assertEquals(0, budget.usedBytes());
        }
    }
    private static FrozenDocumentSource source(String text) {
        return new FrozenDocumentSource(new DocumentSnapshot(new DocumentId(text.substring(0, 1)), 0, List.of(text)),
            DocumentFileMetadata.newDocument(1), ResourceBinding.unbound());
    }
}
