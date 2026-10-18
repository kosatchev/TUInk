package dev.tuink.tui.ui;

import dev.tuiframe.cell.CellText;
import java.nio.file.Path;

/** Plain, single-line display text shared by application notifications and picker feedback. */
public final class NotificationPresentation {
    private static final String HEX = "0123456789ABCDEF";

    private NotificationPresentation() {}

    /** Escapes controls instead of treating external text as terminal or row markup. */
    public static String singleLine(String text) {
        if (text == null) return "";
        StringBuilder escaped = null;
        for (int index = 0; index < text.length(); index++) {
            char character = text.charAt(index);
            if (!Character.isISOControl(character)) {
                if (escaped != null) escaped.append(character);
                continue;
            }
            if (escaped == null) {
                escaped = new StringBuilder(text.length());
                escaped.append(text, 0, index);
            }
            switch (character) {
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> escaped.append("\\u")
                    .append(HEX.charAt((character >>> 12) & 15))
                    .append(HEX.charAt((character >>> 8) & 15))
                    .append(HEX.charAt((character >>> 4) & 15))
                    .append(HEX.charAt(character & 15));
            }
        }
        return escaped == null ? text : escaped.toString();
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
