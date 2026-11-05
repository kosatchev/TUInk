package dev.tuink.tui;

import static org.junit.jupiter.api.Assertions.*;

import dev.tuink.editor.document.DocumentId;
import dev.tuink.editor.document.DocumentSnapshot;
import dev.tuink.markdown.commonmark.CommonmarkMarkdownParser;
import dev.tuink.render.markdown.MarkdownLayout;
import dev.tuink.render.markdown.MarkdownVisualCopy;
import java.util.List;
import org.junit.jupiter.api.Test;

class MarkdownPreviewCopyBudgetTest {
    @Test void detachedCopyMetadataAddsItsExactChargeToTheExistingLayoutPool() {
        var ready = ready();
        var baseline = withoutCopyMetadata(ready);
        long copyBytes = MarkdownVisualCopy.retainedBytes(ready);
        assertTrue(copyBytes > 0, "A real ready layout must retain its copy plan even before the preference is enabled");
        assertEquals(0, MarkdownVisualCopy.retainedBytes(baseline));
        long baselineBytes;
        try (var budget = new MarkdownPreviewBudget(MarkdownPreviewBudget.Limits.DEFAULT)) {
            try (var lease = budget.result(baseline)) {
                baselineBytes = budget.usedBytes(MarkdownPreviewBudget.Kind.LAYOUT);
            }
            assertEquals(0, budget.usedBytes());
            try (var lease = budget.result(ready)) {
                assertEquals(copyBytes, budget.usedBytes(MarkdownPreviewBudget.Kind.LAYOUT) - baselineBytes);
            }
            assertEquals(0, budget.usedBytes());
        }
    }

    @Test void admissionNeedsTheCopyChargeAndARefusalCannotChangeAnAlreadyRetainedBaseline() {
        var ready = ready();
        var baseline = withoutCopyMetadata(ready);
        long baselineBytes = layoutBytes(baseline);
        assertTrue(MarkdownVisualCopy.retainedBytes(ready) > 0);
        assertTrue(2 * baselineBytes < MarkdownPreviewBudget.Limits.DEFAULT.layoutBytes());

        // Without the new metadata this exact result would fit; its extra copy plan must be admitted too.
        try (var budget = new MarkdownPreviewBudget(limits(baselineBytes))) {
            assertThrows(MarkdownPreviewBudget.Refused.class, () -> budget.result(ready));
            assertEquals(0, budget.usedBytes(), "A refused admission cannot leave a model or layout charge behind");
            try (var retained = budget.result(baseline)) {
                assertEquals(baselineBytes, budget.usedBytes(MarkdownPreviewBudget.Kind.LAYOUT));
            }
            assertEquals(0, budget.usedBytes());
        }

        // Keep one baseline alive and leave exactly another baseline's charge available.
        // This would admit the ready result if its copy metadata were accidentally omitted.
        try (var budget = new MarkdownPreviewBudget(limits(2 * baselineBytes))) {
            try (var retained = budget.result(baseline)) {
                long before = budget.usedBytes();
                long modelBefore = budget.usedBytes(MarkdownPreviewBudget.Kind.MODEL);
                assertThrows(MarkdownPreviewBudget.Refused.class, () -> budget.result(ready));
                assertEquals(before, budget.usedBytes());
                assertEquals(modelBefore, budget.usedBytes(MarkdownPreviewBudget.Kind.MODEL));
                assertEquals(baselineBytes, budget.usedBytes(MarkdownPreviewBudget.Kind.LAYOUT));
                assertSame(ready.document(), baseline.document());
                assertEquals(ready.lines(), baseline.lines());
            }
            assertEquals(0, budget.usedBytes());
        }
    }

    @Test void repeatedLeasesOfTheSameReadyResultShareTheCopyPlanChargeUntilTheLastOwnerRetires() {
        var ready = ready();
        assertTrue(MarkdownVisualCopy.retainedBytes(ready) > 0);
        try (var budget = new MarkdownPreviewBudget(MarkdownPreviewBudget.Limits.DEFAULT)) {
            var first = budget.result(ready);
            long once = budget.usedBytes();
            long layoutOnce = budget.usedBytes(MarkdownPreviewBudget.Kind.LAYOUT);
            assertTrue(layoutOnce > MarkdownVisualCopy.retainedBytes(ready));
            var second = budget.result(ready);
            assertEquals(once, budget.usedBytes());
            assertEquals(layoutOnce, budget.usedBytes(MarkdownPreviewBudget.Kind.LAYOUT));
            first.close();
            assertEquals(once, budget.usedBytes());
            first.close();
            assertEquals(once, budget.usedBytes(), "A retired lease is idempotent");
            second.close();
            assertEquals(0, budget.usedBytes());
            assertEquals(0, budget.usedBytes(MarkdownPreviewBudget.Kind.LAYOUT));
        }
    }

    private static MarkdownLayout.Result ready() {
        var snapshot = new DocumentSnapshot(new DocumentId("copy-budget"), 3, List.of(
            "many normal words for detached copy accounting ".repeat(120).stripTrailing(), "",
            "Before $\\frac{a}{b}$ and again $\\frac{a}{b}$ after.", "",
            "```text", "  indented\tcode", "```"));
        var document = new CommonmarkMarkdownParser().parse(snapshot);
        var fraction = new MarkdownLayout.Box(List.of(" a ", "───", " b "), 1);
        return new MarkdownLayout(24, new MarkdownLayout.EmbeddedRenderer() {
            @Override public MarkdownLayout.Box math(String source) { return fraction; }
        }).layout(document);
    }

    private static MarkdownLayout.Result withoutCopyMetadata(MarkdownLayout.Result ready) {
        return new MarkdownLayout.Result(ready.document(), ready.lines(), ready.columns(), ready.extent());
    }

    private static long layoutBytes(MarkdownLayout.Result result) {
        try (var budget = new MarkdownPreviewBudget(MarkdownPreviewBudget.Limits.DEFAULT);
             var retained = budget.result(result)) {
            return budget.usedBytes(MarkdownPreviewBudget.Kind.LAYOUT);
        }
    }

    private static MarkdownPreviewBudget.Limits limits(long layoutBytes) {
        var defaults = MarkdownPreviewBudget.Limits.DEFAULT;
        return new MarkdownPreviewBudget.Limits(defaults.totalBytes(), defaults.sourceBytes(), defaults.modelBytes(), layoutBytes);
    }
}
