package dev.tuink.render.markdown;

import static org.junit.jupiter.api.Assertions.*;

import dev.tuink.markdown.math.MathExpression;
import dev.tuink.markdown.math.MathNode;
import dev.tuiframe.text.TerminalText;
import java.util.List;
import org.junit.jupiter.api.Test;

class MathLayoutTest {
    private static MathNode.Text t(String text) { return new MathNode.Text(text); }
    private static MathExpression expression(MathNode root) { return new MathExpression("exact TeX", root, List.of()); }

    @Test void fractionPlacesBothTermsAroundItsBaseline() {
        MathLayout.Box box = MathLayout.layout(expression(new MathNode.Fraction(
            new MathNode.Row(List.of(new MathNode.Scripts(t("x"), null, t("2")), t(" + 1"))), t("y"))));
        assertEquals(4, box.height());
        assertEquals(2, box.baseline());
        assertTrue(box.lines().get(0).contains("2"));
        assertTrue(box.lines().get(1).contains("x"));
        assertEquals("─".repeat(box.width()), box.lines().get(2));
        assertEquals("y", box.lines().get(3).strip());
        rectangular(box);
    }

    @Test void towerPlacesEveryExponentOneLevelAboveItsOwnBase() {
        MathNode tower = new MathNode.Scripts(t("2"), null, new MathNode.Scripts(t("3"), null, t("2")));
        MathLayout.Box box = MathLayout.layout(expression(tower));
        assertEquals(List.of("  2", " 3 ", "2  "), box.lines());
        assertEquals(2, box.baseline());
    }

    @Test void scriptsAboveAndBelowAnInlineBaseChangeLineHeight() {
        MathLayout.Box box = MathLayout.layout(expression(new MathNode.Scripts(t("x"), t("i"), t("2"))));
        assertEquals(List.of(" 2", "x ", " i"), box.lines());
        assertEquals(1, box.ascent());
        assertEquals(1, box.descent());
    }

    @Test void integralHasUpperAndLowerLimitsAndAnExtendedSign() {
        MathLayout.Box box = MathLayout.layout(expression(new MathNode.LargeOperator("∫", t("0"), t("∞"))));
        assertEquals(List.of("∞", "⌠", "⎮", "⌡", "0"), box.lines());
        assertEquals(2, box.baseline());
    }

    @Test void nestedRadicalsRetainEveryOverbarAndDoNotFlattenTheRadicand() {
        MathNode root = new MathNode.Radical(new MathNode.Row(List.of(t("2 + "),
            new MathNode.Radical(new MathNode.Row(List.of(t("2 + "), new MathNode.Radical(t("2"), null))), null))), null);
        MathLayout.Box box = MathLayout.layout(expression(root));
        assertEquals(4, box.height());
        assertTrue(box.lines().get(0).contains("─"));
        assertTrue(box.lines().get(1).contains("─"));
        assertTrue(box.lines().get(2).contains("─"));
        assertEquals(3, box.lines().get(3).split("╲╱", -1).length - 1);
        rectangular(box);
    }

    @Test void matrixAlignsFractionsAndPreservesTerminalCellWidths() {
        MathNode.Matrix matrix = new MathNode.Matrix(List.of(
            List.of(t("字"), new MathNode.Fraction(t("1"), t("2"))), List.of(t("α"), t("β"))), "[", "]");
        MathLayout.Box box = MathLayout.layout(expression(matrix));
        assertTrue(box.lines().get(0).startsWith("⎡"));
        assertTrue(box.lines().get(box.height() - 1).endsWith("⎦"));
        assertTrue(box.lines().stream().anyMatch(line -> line.contains("字") && line.contains("─")));
        rectangular(box);
    }

    @Test void layoutBudgetsAndUnknownSourceFallbackRemainBounded() {
        assertThrows(IllegalArgumentException.class, () -> MathLayout.layout(expression(t("long expression")),
            new MathLayout.Limits(4, 4, 64, 8)));
        assertThrows(IllegalArgumentException.class, () -> MathLayout.layout(expression(new MathNode.Fraction(t("a"), t("b"))),
            new MathLayout.Limits(16, 2, 64, 8)));
        MathNode deep = t("x");
        for (int i = 0; i < 80; i++) deep = new MathNode.Radical(deep, null);
        MathExpression source = expression(deep);
        assertThrows(IllegalArgumentException.class, () -> MathLayout.layout(source));
        MathLayout.Box literal = MathLayout.layout(new MathExpression("\\unknown{x}", t("ignored"), List.of("unsupported")));
        assertEquals("\\unknown{x}", literal.lines().get(0));
    }

    @Test void textSanitizesTerminalControlCharacters() {
        MathLayout.Box box = MathLayout.layout(expression(t("x\u001b[31m")));
        assertFalse(box.lines().get(0).contains("\u001b"));
        rectangular(box);
    }

    private static void rectangular(MathLayout.Box box) {
        for (String line : box.lines()) assertEquals(box.width(), TerminalText.visualWidth(line, 4), line);
        assertEquals(box.height(), box.lines().size());
        assertTrue(box.baseline() >= 0 && box.baseline() < box.height());
    }
}
