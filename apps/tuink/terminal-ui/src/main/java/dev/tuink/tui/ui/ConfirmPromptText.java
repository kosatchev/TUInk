package dev.tuink.tui.ui;

import dev.tuiframe.cell.CellText;
import java.nio.file.Path;

/** Keeps the file identity and question visible in TUInk's inline confirmations. */
public final class ConfirmPromptText {
    private ConfirmPromptText() {
    }

    public static String fileQuestion(String prefix, Path path, String suffix, int maxCells) {
        String full = path == null ? "--no name--" : PathPresentation.full(path);
        String name = path == null ? full : PathPresentation.name(path);
        int nameCells = maxCells - CellText.width(prefix) - CellText.width(suffix);
        String identifier;
        if (CellText.width(full) <= nameCells) {
            identifier = full;
        } else if (PathPresentation.encodedName(path)) {
            // Encoded names are precise byte identities. A partial label cannot authorize a file action.
            identifier = name;
        } else if (CellText.width(name) < nameCells) {
            identifier = "…" + tail(full, nameCells - 1);
        } else if (CellText.width(name) <= nameCells) {
            identifier = name;
        } else if (nameCells >= 7) {
            identifier = PathPresentation.fit(name, nameCells, nameCells / 2);
        } else {
            // Returning the intact minimum question makes ConfirmBarView choose
            // its resize hint instead of accepting an unidentified file action.
            identifier = name;
        }
        return prefix + identifier + suffix;
    }

    /** A wrapping prompt may shorten the parent context, but never the filename identity itself. */
    public static String wrappingFileQuestion(String prefix, Path path, String suffix, int preferredCells) {
        String full = path == null ? "--no name--" : PathPresentation.full(path);
        String name = path == null ? full : PathPresentation.name(path);
        String identity = CellText.width(prefix + full + suffix) <= preferredCells ? full : name;
        return prefix + identity + suffix;
    }

    private static String tail(String text, int cells) {
        return PathPresentation.clip(text, Math.max(0, CellText.width(text) - cells), cells);
    }
}
