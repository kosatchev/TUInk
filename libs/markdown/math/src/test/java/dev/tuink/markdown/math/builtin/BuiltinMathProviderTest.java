package dev.tuink.markdown.math.builtin;

import static org.junit.jupiter.api.Assertions.*;

import dev.tuink.markdown.math.MathExpression;
import dev.tuink.markdown.math.MathNode;
import java.util.List;
import org.junit.jupiter.api.Test;

class BuiltinMathProviderTest {
    private final BuiltinMathProvider provider = new BuiltinMathProvider();

    @Test void allEightRequiredFormulaFamiliesHaveStructuredNotation() {
        List<String> examples = List.of(
            "\\int_0^{\\infty} e^{-x^2}\\,dx = \\frac{\\sqrt{\\pi}}{2}",
            "x_{1,2} = \\frac{-b \\pm \\sqrt{b^2 - 4ac}}{2a}",
            "2^{3^2} = 512",
            "r = \\sqrt{2 + \\sqrt{2 + \\sqrt{2}}} \\approx 1.96157",
            "e^x = \\sum_{n=0}^{\\infty} \\frac{x^n}{n!}",
            "\\begin{bmatrix} a & b \\\\ c & d \\end{bmatrix}^{-1} = \\frac{1}{ad-bc} \\begin{bmatrix} d & -b \\\\ -c & a \\end{bmatrix}",
            "\\frac{\\partial u}{\\partial t} = \\kappa \\frac{\\partial^2 u}{\\partial x^2}",
            "\\varphi = 1 + \\cfrac{1}{1 + \\cfrac{1}{1 + \\ddots}}"
        );
        for (String source : examples) {
            MathExpression expression = provider.parse(source);
            assertTrue(expression.supported(), () -> source + ": " + expression.diagnostics());
            assertFalse(expression.root() instanceof MathNode.Text, source);
            assertEquals(source, expression.source());
        }
    }

    @Test void powersRetainTheirTreeAndDoNotEvaluateOrRegroup() {
        MathNode.Scripts outer = assertInstanceOf(MathNode.Scripts.class, provider.parse("2^{3^2}").root());
        assertEquals(new MathNode.Text("2"), outer.base());
        MathNode.Scripts inner = assertInstanceOf(MathNode.Scripts.class, outer.superscript());
        assertEquals(new MathNode.Text("3"), inner.base());
        assertEquals(new MathNode.Text("2"), inner.superscript());
        MathNode.Scripts grouped = assertInstanceOf(MathNode.Scripts.class, provider.parse("(2^3)^2").root());
        assertInstanceOf(MathNode.Delimited.class, grouped.base());
        assertEquals(new MathNode.Text("2"), grouped.superscript());
    }

    @Test void groupedScriptsAndBothBoundsArePreserved() {
        MathNode.Scripts scripts = assertInstanceOf(MathNode.Scripts.class, provider.parse("x_i^{a+b}").root());
        assertEquals(new MathNode.Text("i"), scripts.subscript());
        assertInstanceOf(MathNode.Row.class, scripts.superscript());
        MathNode.LargeOperator sum = assertInstanceOf(MathNode.LargeOperator.class,
            provider.parse("\\sum\\limits_{n=0}^{\\infty}").root());
        assertEquals("∑", sum.symbol());
        assertInstanceOf(MathNode.Row.class, sum.lower());
        assertEquals(new MathNode.Text("∞"), sum.upper());
    }

    @Test void matrixKeepsRowsCellsAndNestedCellExpressions() {
        MathNode.Matrix matrix = assertInstanceOf(MathNode.Matrix.class,
            provider.parse("\\begin{bmatrix}\\frac{a}{b}&2\\\\3&\\sqrt{x}\\end{bmatrix}").root());
        assertEquals(2, matrix.rows().size());
        assertEquals(2, matrix.rows().get(0).size());
        assertEquals("[", matrix.left());
        assertInstanceOf(MathNode.Fraction.class, matrix.rows().get(0).get(0));
        assertInstanceOf(MathNode.Radical.class, matrix.rows().get(1).get(1));
    }

    @Test void textFunctionsIndexedRootsAndLeftRightDelimitersAreSupported() {
        for (String source : List.of("e^{i\\pi}+1=0", "5+2=7", "\\sin x", "\\text{hello world}",
            "\\sqrt[3]{x}", "\\left(\\frac{a}{b}\\right)", "\\mathrm{dx}"))
            assertTrue(provider.parse(source).supported(), source);
        MathNode.Radical radical = assertInstanceOf(MathNode.Radical.class, provider.parse("\\sqrt[3]{x}").root());
        assertEquals(new MathNode.Text("3"), radical.index());
    }

    @Test void malformedOrUnsupportedNotationIsLocalAndRetainsExactSource() {
        for (String source : List.of("\\unknown{x}", "\\frac{1}", "{x", "x}", "x^2^3", "x_i_j", "\\",
            "\\begin{bmatrix}a&b\\\\c\\end{bmatrix}", "\\begin{bmatrix}x\\end{matrix}", "\\left(x")) {
            MathExpression expression = provider.parse(source);
            assertFalse(expression.supported(), source);
            assertEquals(source, expression.source());
            assertFalse(expression.diagnostics().isEmpty());
        }
        assertTrue(provider.parse("x^2").supported(), "Provider has no shared mutable parse state");
    }

    @Test void budgetsRejectBeforeUnboundedNotationCanBeBuilt() {
        assertFalse(new BuiltinMathProvider(new BuiltinMathProvider.Limits(8, 8, 32, 2, 2))
            .parse("x".repeat(9)).supported());
        assertFalse(new BuiltinMathProvider(new BuiltinMathProvider.Limits(1024, 8, 1024, 2, 2))
            .parse("{".repeat(16) + "x" + "}".repeat(16)).supported());
        assertFalse(new BuiltinMathProvider(new BuiltinMathProvider.Limits(1024, 48, 3, 2, 2))
            .parse("abcdef").supported());
        assertFalse(new BuiltinMathProvider(new BuiltinMathProvider.Limits(1024, 48, 1024, 1, 2))
            .parse("\\begin{matrix}a\\\\b\\end{matrix}").supported());
        assertFalse(new BuiltinMathProvider(new BuiltinMathProvider.Limits(1024, 48, 1024, 2, 1))
            .parse("\\begin{matrix}a&b\\end{matrix}").supported());
    }

    @Test void cooperativeCancellationKeepsInterruptAndReturnsDiagnostic() {
        Thread.currentThread().interrupt();
        try {
            MathExpression cancelled = provider.parse("x+1");
            assertFalse(cancelled.supported());
            assertTrue(Thread.currentThread().isInterrupted());
        } finally { Thread.interrupted(); }
    }
}
