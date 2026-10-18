package dev.tuink.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class IndentationContextTest {
    @Test
    void distantQueriesHaveAHardLineBudgetAndPreserveTheirBoundedProgress() {
        AtomicInteger reads = new AtomicInteger();
        AtomicReference<String> current = new AtomicReference<>("if ready:");
        int last = 10_000;
        IndentationContext context = new IndentationContext(line -> {
            reads.incrementAndGet();
            return line == last ? current.get() : "work()";
        });
        context.synchronize(1, 1, 0, last + 1, Path.of("main.py"));
        String code = null;
        for (int attempt = 0; attempt < 20 && code == null; attempt++) {
            reads.set(0);
            code = context.codeBeforeCursor(last, current.get().length());
            assertTrue(reads.get() <= IndentationContext.MAX_SCAN_LINES + 1);
        }
        assertNotNull(code, "repeated bounded queries must eventually reach this nearby target");
        for (int revision = 2; revision < 102; revision++) {
            current.set("if ready:" + " ".repeat(revision));
            context.synchronize(1, revision, last, last + 1, Path.of("main.py"));
            reads.set(0);
            assertNotNull(context.codeBeforeCursor(last, current.get().length()));
            assertEquals(1, reads.get(), "typing on the cached line must not scan its document prefix again");
        }
    }

    @Test
    void replacingAnOpeningDelimiterAndInsertingLinesInvalidateOnlyProvenBoundaries() {
        TextModel model = new TextModel(new SimpleLineBuffer());
        model.replaceLines(List.of("payload = '''", "    if ready:", "    value"));
        IndentationContext context = new IndentationContext(model::getLine);
        synchronize(context, model, "main.py");
        assertNull(context.codeBeforeCursor(1, model.getLine(1).length()));
        model.replaceRange(new Position(0, 0), new Position(0, model.getLine(0).length()), "value = 1");
        synchronize(context, model, "main.py");
        assertNotNull(context.codeBeforeCursor(1, model.getLine(1).length()));
        model.replaceRange(new Position(0, 0), new Position(0, 0), "payload = '''\n");
        synchronize(context, model, "main.py");
        assertNull(context.codeBeforeCursor(2, model.getLine(2).length()));
        model.replaceRange(new Position(0, 0), new Position(1, 0), "");
        synchronize(context, model, "main.py");
        assertNotNull(context.codeBeforeCursor(1, model.getLine(1).length()));
        model.replaceLines(List.of("payload = '''", "    if ready:"));
        synchronize(context, model, "main.py");
        assertNull(context.codeBeforeCursor(1, model.getLine(1).length()));
    }

    @Test
    void distantAndOversizedContextCannotBeAssumedToBeCode() {
        AtomicInteger reads = new AtomicInteger();
        IndentationContext context = new IndentationContext(line -> {
            reads.incrementAndGet();
            return line == 0 ? "/*" + "x".repeat(IndentationContext.MAX_SCAN_CODE_UNITS) : "if (ready) {";
        });
        context.synchronize(1, 1, 0, 10_000_000, Path.of("Main.java"));
        assertNull(context.codeBeforeCursor(9_999_999, 12));
        assertEquals(2, reads.get());
    }

    private static void synchronize(IndentationContext context, TextModel model, String file) {
        context.synchronize(model.contentEpoch(), model.revision(), model.firstChangedLineSince(context.revision()),
            model.lineCount(), Path.of(file));
    }
}
