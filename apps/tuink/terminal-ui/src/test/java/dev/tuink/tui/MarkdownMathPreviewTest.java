package dev.tuink.tui;

import dev.tuink.editor.document.DocumentId;
import dev.tuink.editor.document.DocumentSnapshot;
import dev.tuink.markdown.MarkdownBlock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MarkdownMathPreviewTest {
    @Test void everyRequiredMathExampleFlowsFromMarkdownSourceToActualUnicodeLayout() throws Exception {
        var source = new DocumentSnapshot(new DocumentId("required-math"), 4,
            Files.readAllLines(Path.of("../samples/demo_markdown_preview.md")));
        try (var service = new MarkdownPreviewService(() -> {})) {
            var result = service.submit(source, 96).get(8, TimeUnit.SECONDS);
            assertEquals(9, result.document().blocks().stream().filter(MarkdownBlock.Math.class::isInstance).count());
            String text = String.join("\n", result.lines().stream().filter(line -> line.kind().equals("math"))
                .map(line -> line.text()).toList());
            for (String required : List.of("⌠", "╲╱", "⎡", "∂", "─")) assertTrue(text.contains(required), text);
            assertTrue(text.contains("∑") || text.contains("⎲") && text.contains("⎳"), text);
            assertFalse(text.contains("\\frac"), text);
            assertFalse(text.contains("[math:"), text);
            assertEquals(4, result.document().revision());
        }
    }
}
