package dev.tuink.compat;

import dev.tuink.editor.document.DocumentId;
import dev.tuink.editor.document.DocumentSnapshot;
import dev.tuink.markdown.MarkdownBlock;
import dev.tuink.markdown.commonmark.CommonmarkMarkdownParser;
import dev.tuink.markdown.math.builtin.BuiltinMathProvider;
import dev.tuink.markdown.mermaid.Diagram;
import dev.tuink.markdown.mermaid.builtin.BuiltinMermaidProvider;
import java.util.List;

/** External consumer: no terminal, platform storage, engine AST or mutable editor classes. */
public final class PortableMarkdownApiProbe {
    public static void main(String[] args) {
        var document = new CommonmarkMarkdownParser().parse(new DocumentSnapshot(new DocumentId("portable-markdown"), 3,
            List.of("# Portable 😀", "", "Hello **world** $x_i^2$.", "", "| a | b |", "|---|---|", "| 1 | 2 |")));
        require(document.revision() == 3 && document.blocks().get(0) instanceof MarkdownBlock.Heading);
        require(document.blocks().stream().anyMatch(block -> block instanceof MarkdownBlock.Table));
        require(document.projection().text().contains("world"));
        var math = new BuiltinMathProvider();
        for (String source : List.of("\\int_0^{\\infty} e^{-x^2}\\,dx = \\frac{\\sqrt{\\pi}}{2}",
            "x_{1,2}=\\frac{-b\\pm\\sqrt{b^2-4ac}}{2a}", "2^{3^2}=512",
            "\\sqrt{2+\\sqrt{2+\\sqrt{2}}}", "e^x=\\sum_{n=0}^{\\infty}\\frac{x^n}{n!}",
            "\\begin{bmatrix}a&b\\\\c&d\\end{bmatrix}^{-1}",
            "\\frac{\\partial u}{\\partial t}=\\kappa\\frac{\\partial^2u}{\\partial x^2}",
            "\\varphi=1+\\cfrac{1}{1+\\cfrac{1}{1+\\ddots}}")) {
            var expression = math.parse(source);
            require(expression.supported());
            require(expression.source().equals(source));
        }
        var mermaid = new BuiltinMermaidProvider();
        require(!(mermaid.parse("flowchart LR\n A[Start] --> B{Choice}\n B -->|yes| C[Done]") instanceof Diagram.Unsupported));
        require(!(mermaid.parse("sequenceDiagram\n participant A as Editor\n participant B as Renderer\n A->>B: snapshot\n B-->>A: result") instanceof Diagram.Unsupported));
        System.out.println("PASS: portable Markdown/math/Mermaid API on Java " + System.getProperty("java.specification.version"));
    }
    private static void require(boolean condition) { if (!condition) throw new AssertionError("Portable Markdown contract"); }
}
