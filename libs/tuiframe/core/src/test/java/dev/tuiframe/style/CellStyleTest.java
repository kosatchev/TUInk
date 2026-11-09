package dev.tuiframe.style;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class CellStyleTest {
    @Test
    void equalValuesHaveValueEqualityAndHashCode() {
        CellStyle first = new CellStyle(CellColor.CYAN, CellColor.BLACK, true, false);
        CellStyle second = new CellStyle(CellColor.CYAN, CellColor.BLACK, true, false);

        assertEquals(first, second);
        assertEquals(first.hashCode(), second.hashCode());
    }

    @Test
    void foregroundFactoryKeepsOtherAttributesAtDefaults() {
        assertEquals(
            new CellStyle(CellColor.GREEN, CellColor.DEFAULT, false, false),
            CellStyle.fg(CellColor.GREEN)
        );
    }

    @Test
    void defaultStyleUsesDefaultColorsAndNoDecorations() {
        assertEquals(CellColor.DEFAULT, CellStyle.DEFAULT.fg());
        assertEquals(CellColor.DEFAULT, CellStyle.DEFAULT.bg());
        assertFalse(CellStyle.DEFAULT.bold());
        assertFalse(CellStyle.DEFAULT.underline());
    }

    @Test
    void inversionIsAValueAttributeIncludingWhenBothColorsAreDefaults() {
        var normal = CellStyle.DEFAULT; var inverse = normal.inverted();
        assertTrue(inverse.inverse()); assertNotEquals(normal, inverse);
        assertEquals(normal, inverse.inverted()); assertTrue(inverse.emphasis(true, true).inverse());
        assertEquals(inverse, new CellStyle(normal.fg(), normal.bg(), false, false, true));
    }

    @Test
    void colorsAreRequired() {
        assertThrows(
            IllegalArgumentException.class,
            () -> new CellStyle(null, CellColor.DEFAULT, false, false)
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> new CellStyle(CellColor.DEFAULT, null, false, false)
        );
    }
}
