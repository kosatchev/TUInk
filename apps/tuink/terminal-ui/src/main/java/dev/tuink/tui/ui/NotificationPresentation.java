package dev.tuink.tui.ui;

import dev.tuiframe.explorer.ui.PathPresentation;

import dev.tuiframe.cell.CellText;
import dev.tuiframe.text.TerminalPlainText;
import java.nio.file.Path;

/** Plain, single-line display text shared by application notifications and picker feedback. */
public final class NotificationPresentation {
    private NotificationPresentation() {}

    /** Escapes controls instead of treating external text as terminal or row markup. */
    public static String singleLine(String text) {
        return TerminalPlainText.singleLine(text);
    }

    /** Keeps the leading explanation visible and uses an ellipsis when it cannot fit. */
    public static String fit(String text, int cells) {
        if (cells <= 0) return "";
        String plain = singleLine(text);
        if (CellText.width(plain) <= cells) return plain;
        return PathPresentation.clip(plain, 0, cells - 1) + "…";
    }

    /** Uses a reversible URI for controls or native filename bytes that cannot be displayed literally. */
    public static String path(Path path) {
        return PathPresentation.full(path);
    }

    /** Preserves the explanation before fitting an optional path into the remaining cells. */
    public static String fitReasonWithPath(String summary, Path path, int cells) {
        String reason = singleLine(summary);
        int reasonCells = CellText.width(reason);
        int contextCells = cells - reasonCells - 2;
        if (path == null || contextCells < 3) return fit(reason, cells);
        String context = path(path);
        int nameCells = path.getFileName() == null ? 0 : CellText.width(PathPresentation.name(path));
        return reason + ": " + PathPresentation.fit(context, contextCells, nameCells);
    }
}
