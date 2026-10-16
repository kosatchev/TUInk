package dev.tuiframe.widget;

import dev.tuiframe.cell.CellText;

/** Consumer-owned atomic label boundaries, independent of a widget's geometry or values. */
public interface LabelPolicy {
    LabelPolicy DEFAULT = new LabelPolicy() {
        @Override
        public CellText.FittedText fitMiddle(String text, int cells, int preferredTailCells, int initialColumn) {
            return CellText.fitMiddle(text, cells, preferredTailCells, initialColumn);
        }

        @Override
        public String clip(String text, int leftCells, int cells) {
            return CellText.clip(text, leftCells, cells);
        }
    };

    /** Returns display-ready text within the cell budget, preserving completeness and tab origin. */
    CellText.FittedText fitMiddle(String text, int cells, int preferredTailCells, int initialColumn);

    /** Clips a viewport without shifting visible text; partial atomic units may become spaces. */
    String clip(String text, int leftCells, int cells);
}
