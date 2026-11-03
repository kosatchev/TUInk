package dev.tuink.tui;

import dev.tuink.editor.document.DocumentId;
import dev.tuink.editor.document.DocumentSnapshot;
import dev.tuink.markdown.MarkdownBlock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MarkdownMermaidPreviewTest {
    @Test void requiredFlowchartAndSequenceRenderThroughTheActualPreviewPipeline() throws Exception {
        var source = new DocumentSnapshot(new DocumentId("required-mermaid"), 5,
            Files.readAllLines(Path.of("../samples/demo_markdown_preview.md")));
        try (var service = new MarkdownPreviewService(() -> {})) {
            var result = service.submit(source, 96).get(8, TimeUnit.SECONDS);
            assertEquals(2, result.document().blocks().stream().filter(MarkdownBlock.Mermaid.class::isInstance).count());
            String diagrams = String.join("\n", result.lines().stream().filter(line -> line.kind().equals("mermaid"))
                .map(line -> line.text()).toList());
            assertTrue(diagrams.contains("Open Markdown"), diagrams);
            assertTrue(diagrams.contains("User"), diagrams);
            assertTrue(diagrams.contains("New revision snapshot"), diagrams);
            assertTrue(diagrams.contains("┌") && diagrams.contains("→"), diagrams);
            assertFalse(diagrams.contains("flowchart LR"), diagrams);
            assertFalse(diagrams.contains("sequenceDiagram"), diagrams);
            assertFalse(diagrams.contains("[Mermaid:"), diagrams);
        }
    }
}
