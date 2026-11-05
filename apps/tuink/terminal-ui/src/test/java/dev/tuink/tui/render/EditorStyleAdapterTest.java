package dev.tuink.tui.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import dev.tuink.render.Color;
import dev.tuink.render.Style;
import dev.tuiframe.style.CellColor;
import dev.tuiframe.style.CellStyle;
import org.junit.jupiter.api.Test;

class EditorStyleAdapterTest {
    @Test
    void convertsEveryEditorStyleWithoutLosingColorsOrDecorations() {
        for (Color foreground : Color.values()) {
            for (Color background : Color.values()) {
                for (boolean bold : new boolean[] {false, true}) {
                    for (boolean underline : new boolean[] {false, true}) {
                        Style editorStyle = new Style(foreground, background, bold, underline);

                        CellStyle cellStyle = EditorStyleAdapter.toCell(editorStyle);

                        assertEquals(expectedCellColor(foreground), cellStyle.fg());
                        assertEquals(expectedCellColor(background), cellStyle.bg());
                        assertEquals(bold, cellStyle.bold());
                        assertEquals(underline, cellStyle.underline());
                        assertEquals(
                            editorStyle,
                            EditorStyleAdapter.toEditor(cellStyle),
                            () -> "round trip failed for " + editorStyle
                        );
                    }
                }
            }
        }
    }

    @Test
    void convertsEveryCellStyleBackWithoutLosingInformation() {
        for (CellColor foreground : CellColor.values()) {
            for (CellColor background : CellColor.values()) {
                CellStyle cellStyle = new CellStyle(foreground, background, true, true);

                assertEquals(
                    cellStyle,
                    EditorStyleAdapter.toCell(EditorStyleAdapter.toEditor(cellStyle)),
                    () -> "round trip failed for " + cellStyle
                );
            }
        }
    }

    @Test
    void reusesEditorStylesAcrossEqualCellStyles() {
        Style first = new Style(Color.CYAN, Color.BLACK, true, false);
        CellStyle mapped = EditorStyleAdapter.toCell(first);

        assertSame(
            EditorStyleAdapter.toEditor(mapped),
            EditorStyleAdapter.toEditor(new CellStyle(CellColor.CYAN, CellColor.BLACK, true, false))
        );
    }

    private static CellColor expectedCellColor(Color color) {
        return switch (color) {
            case DEFAULT -> CellColor.DEFAULT;
            case BLACK -> CellColor.BLACK;
            case RED -> CellColor.RED;
            case GREEN -> CellColor.GREEN;
            case YELLOW -> CellColor.YELLOW;
            case BLUE -> CellColor.BLUE;
            case MAGENTA -> CellColor.MAGENTA;
            case CYAN -> CellColor.CYAN;
            case WHITE -> CellColor.WHITE;
            case BRIGHT_BLACK -> CellColor.BRIGHT_BLACK;
            case BRIGHT_RED -> CellColor.BRIGHT_RED;
            case BRIGHT_GREEN -> CellColor.BRIGHT_GREEN;
            case BRIGHT_YELLOW -> CellColor.BRIGHT_YELLOW;
            case BRIGHT_BLUE -> CellColor.BRIGHT_BLUE;
            case BRIGHT_MAGENTA -> CellColor.BRIGHT_MAGENTA;
            case BRIGHT_CYAN -> CellColor.BRIGHT_CYAN;
            case BRIGHT_WHITE -> CellColor.BRIGHT_WHITE;
            case DARK_GRAY -> CellColor.DARK_GRAY;
            case GRAY_128 -> CellColor.GRAY_128;
            case GRAY_158 -> CellColor.GRAY_158;
            case GRAY_188 -> CellColor.GRAY_188;
            case GRAY_218 -> CellColor.GRAY_218;
            case GRAY_238 -> CellColor.GRAY_238;
            case GRAY_028 -> CellColor.GRAY_028;
        };
    }
}
