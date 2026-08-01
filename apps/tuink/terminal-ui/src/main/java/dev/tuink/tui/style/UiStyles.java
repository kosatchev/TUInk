package dev.tuink.tui.style;

import dev.tuink.render.Color;
import dev.tuink.render.Style;
import dev.tuink.terminal.style.CellColor;
import dev.tuink.terminal.style.CellStyle;
import java.util.List;

/**
 * Centralized style palette for TUI widgets and renderer defaults.
 */
public final class UiStyles {
    private UiStyles() {
    }

    public static Palette forTheme(Theme theme) {
        return switch (theme) {
            case BLUE -> bluePalette();
            case CYAN -> cyanPalette();
            case LIGHT -> lightPalette();
        };
    }

    public enum Theme {
        CYAN("Cyan"),
        BLUE("Blue"),
        LIGHT("Light");

        private final String label;

        Theme(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }

        public Theme next() {
            return switch (this) {
                case CYAN -> BLUE;
                case BLUE -> LIGHT;
                case LIGHT -> CYAN;
            };
        }

        public static Theme parse(String raw) {
            if (raw == null) {
                return CYAN;
            }
            return switch (raw.trim().toLowerCase()) {
                case "cyan" -> CYAN;
                case "blue" -> BLUE;
                case "light" -> LIGHT;
                default -> throw new IllegalArgumentException("Unknown theme: " + raw + " (expected: blue, cyan, light)");
            };
        }
    }

    public record Palette(
        CellStyle promptAlert,
        CellStyle topbarFilenameHighlight,
        CellStyle menuPopup,
        CellStyle menuSelected,
        CellStyle menuTopSelected,
        CellStyle menuSelectedMono,
        CellStyle tabbar,
        CellStyle tabbarMono,
        CellStyle tabbarActive,
        CellStyle tabbarActiveMono,
        CellStyle dialog,
        CellStyle dialogBorder,
        CellStyle dialogTitle,
        CellStyle dialogSelectedMono,
        CellStyle dialogScrollTrack,
        CellStyle dialogScrollThumb,
        CellStyle searchBar,
        CellStyle notifySuccess,
        CellStyle notifyInfo,
        CellStyle notifyWarning,
        CellStyle notifyError,
        CellStyle contextMenuDisabledItem,
        CellStyle renderGutter,
        CellStyle renderGutterActive,
        CellStyle renderGutterAdded,
        CellStyle renderGutterModified,
        CellStyle renderGutterDeleted,
        CellStyle renderGutterAddedHistorical,
        CellStyle renderGutterModifiedHistorical,
        CellStyle renderGutterDeletedHistorical,
        Style renderSelection,
        Style renderSearchMatch,
        Style renderCurrentSearchMatch,
        Style renderBracketMatch,
        Style renderQuoteMatch,
        Style renderUnmatchedBracket,
        List<Style> renderRainbowBrackets,
        CellStyle renderTopbar,
        CellStyle renderStatus,
        CellStyle renderInteractive,
        CellStyle renderNotification,
        CellStyle renderLeadingWhitespace,
        CellStyle renderScrollTrack,
        CellStyle renderScrollThumb
    ) {
    }

    private static Palette cyanPalette() {
        return new Palette(
            cellStyle(CellColor.BRIGHT_WHITE, CellColor.RED),               // promptAlert
            cellStyle(CellColor.BRIGHT_WHITE, CellColor.CYAN, true, false), // topbarFilenameHighlight
            cellStyle(CellColor.BLACK, CellColor.BRIGHT_WHITE),             // menuPopup
            cellStyle(CellColor.BRIGHT_WHITE, CellColor.CYAN),              // menuSelected
            cellStyle(CellColor.CYAN, CellColor.BRIGHT_WHITE),              // menuTopSelected
            cellStyle(CellColor.DEFAULT, CellColor.DEFAULT),                // menuSelectedMono
            cellStyle(CellColor.BLACK, CellColor.BRIGHT_WHITE),             // tabbar
            cellStyle(CellColor.DEFAULT, CellColor.BRIGHT_WHITE),           // tabbarMono
            cellStyle(CellColor.CYAN, CellColor.DEFAULT),                   // tabbarActive
            cellStyle(CellColor.DEFAULT, CellColor.DEFAULT),                // tabbarActiveMono
            cellStyle(CellColor.BLACK, CellColor.BRIGHT_WHITE),             // dialog
            cellStyle(CellColor.BLACK, CellColor.BRIGHT_WHITE, true, false), // dialogBorder
            cellStyle(CellColor.BRIGHT_WHITE, CellColor.CYAN, true, false), // dialogTitle
            cellStyle(CellColor.DEFAULT, CellColor.DEFAULT),                // dialogSelectedMono
            cellStyle(CellColor.BRIGHT_BLACK, CellColor.BRIGHT_WHITE),      // dialogScrollTrack
            cellStyle(CellColor.BLACK, CellColor.BRIGHT_YELLOW),            // dialogScrollThumb
            cellStyle(CellColor.BRIGHT_WHITE, CellColor.CYAN),              // searchBar
            cellStyle(CellColor.BLACK, CellColor.BRIGHT_GREEN),             // notifySuccess
            cellStyle(CellColor.BRIGHT_WHITE, CellColor.BLUE),              // notifyInfo
            cellStyle(CellColor.BLACK, CellColor.BRIGHT_YELLOW),            // notifyWarning
            cellStyle(CellColor.BRIGHT_WHITE, CellColor.RED),               // notifyError
            cellStyle(CellColor.BRIGHT_BLACK, CellColor.BRIGHT_WHITE),      // contextMenuDisabledItem
            cellStyle(CellColor.BRIGHT_BLACK, CellColor.BLACK),             // renderGutter
            cellStyle(CellColor.BRIGHT_WHITE, CellColor.CYAN, true, false), // renderGutterActive
            cellStyle(CellColor.BRIGHT_GREEN, CellColor.BLACK, true, false), // renderGutterAdded
            cellStyle(CellColor.BRIGHT_YELLOW, CellColor.BLACK, true, false), // renderGutterModified
            cellStyle(CellColor.BRIGHT_RED, CellColor.BLACK, true, false), // renderGutterDeleted
            cellStyle(CellColor.BRIGHT_BLACK, CellColor.BLACK, false, false), // renderGutterAddedHistorical
            cellStyle(CellColor.BRIGHT_BLACK, CellColor.BLACK, false, false), // renderGutterModifiedHistorical
            cellStyle(CellColor.BRIGHT_BLACK, CellColor.BLACK, false, false), // renderGutterDeletedHistorical
            new Style(Color.BLACK, Color.BRIGHT_CYAN, false, false), // renderSelection
            new Style(Color.BLACK, Color.BRIGHT_YELLOW, false, false), // renderSearchMatch
            new Style(Color.BLACK, Color.BRIGHT_CYAN, false, false), // renderCurrentSearchMatch
            new Style(Color.BLACK, Color.BRIGHT_YELLOW, true, false), // renderBracketMatch
            new Style(Color.BLACK, Color.BRIGHT_YELLOW, true, false), // renderQuoteMatch
            new Style(Color.BRIGHT_WHITE, Color.RED, true, false), // renderUnmatchedBracket
            List.of(
                new Style(Color.BRIGHT_BLUE, Color.DEFAULT, true, false),
                new Style(Color.BRIGHT_CYAN, Color.DEFAULT, true, false),
                new Style(Color.BRIGHT_GREEN, Color.DEFAULT, true, false),
                new Style(Color.BRIGHT_YELLOW, Color.DEFAULT, true, false),
                new Style(Color.BRIGHT_MAGENTA, Color.DEFAULT, true, false),
                new Style(Color.BRIGHT_RED, Color.DEFAULT, true, false)
            ),                                                    // renderRainbowBrackets
            cellStyle(CellColor.BRIGHT_WHITE, CellColor.CYAN, true, false),              // renderTopbar
            cellStyle(CellColor.BLACK, CellColor.BRIGHT_WHITE),             // renderStatus
            cellStyle(CellColor.BLACK, CellColor.BRIGHT_YELLOW),            // renderInteractive
            cellStyle(CellColor.BLACK, CellColor.BRIGHT_GREEN),             // renderNotification
            cellStyle(CellColor.BRIGHT_BLACK, CellColor.DEFAULT),           // renderLeadingWhitespace
            cellStyle(CellColor.BRIGHT_BLACK, CellColor.DEFAULT),           // renderScrollTrack
            cellStyle(CellColor.BRIGHT_WHITE, CellColor.DEFAULT)            // renderScrollThumb
        );
    }

    private static Palette bluePalette() {
        return new Palette(
            cellStyle(CellColor.BRIGHT_WHITE, CellColor.RED),               // promptAlert
            cellStyle(CellColor.BRIGHT_WHITE, CellColor.BLUE, true, false), // topbarFilenameHighlight
            cellStyle(CellColor.BLACK, CellColor.BRIGHT_WHITE),             // menuPopup
            cellStyle(CellColor.BRIGHT_WHITE, CellColor.BLUE),              // menuSelected
            cellStyle(CellColor.BLUE, CellColor.BRIGHT_WHITE),              // menuTopSelected
            cellStyle(CellColor.DEFAULT, CellColor.DEFAULT),                // menuSelectedMono
            cellStyle(CellColor.BLACK, CellColor.BRIGHT_WHITE),             // tabbar
            cellStyle(CellColor.DEFAULT, CellColor.BRIGHT_WHITE),           // tabbarMono
            cellStyle(CellColor.BLUE, CellColor.DEFAULT),                   // tabbarActive
            cellStyle(CellColor.DEFAULT, CellColor.DEFAULT),                // tabbarActiveMono
            cellStyle(CellColor.BLACK, CellColor.BRIGHT_WHITE),             // dialog
            cellStyle(CellColor.BLACK, CellColor.BRIGHT_WHITE, true, false), // dialogBorder
            cellStyle(CellColor.BRIGHT_WHITE, CellColor.BLUE, true, false), // dialogTitle
            cellStyle(CellColor.DEFAULT, CellColor.DEFAULT),                // dialogSelectedMono
            cellStyle(CellColor.BRIGHT_BLACK, CellColor.BRIGHT_WHITE),      // dialogScrollTrack
            cellStyle(CellColor.BLACK, CellColor.BRIGHT_YELLOW),            // dialogScrollThumb
            cellStyle(CellColor.BRIGHT_WHITE, CellColor.BLUE),              // searchBar
            cellStyle(CellColor.BLACK, CellColor.BRIGHT_GREEN),             // notifySuccess
            cellStyle(CellColor.BRIGHT_WHITE, CellColor.BLUE),              // notifyInfo
            cellStyle(CellColor.BLACK, CellColor.BRIGHT_YELLOW),            // notifyWarning
            cellStyle(CellColor.BRIGHT_WHITE, CellColor.RED),               // notifyError
            cellStyle(CellColor.BRIGHT_BLACK, CellColor.BRIGHT_WHITE),      // contextMenuDisabledItem
            cellStyle(CellColor.BRIGHT_BLACK, CellColor.BLACK),             // renderGutter
            cellStyle(CellColor.BRIGHT_WHITE, CellColor.BLUE, true, false), // renderGutterActive
            cellStyle(CellColor.BRIGHT_GREEN, CellColor.BLACK, true, false), // renderGutterAdded
            cellStyle(CellColor.BRIGHT_YELLOW, CellColor.BLACK, true, false), // renderGutterModified
            cellStyle(CellColor.BRIGHT_RED, CellColor.BLACK, true, false), // renderGutterDeleted
            cellStyle(CellColor.BRIGHT_BLACK, CellColor.BLACK, false, false), // renderGutterAddedHistorical
            cellStyle(CellColor.BRIGHT_BLACK, CellColor.BLACK, false, false), // renderGutterModifiedHistorical
            cellStyle(CellColor.BRIGHT_BLACK, CellColor.BLACK, false, false), // renderGutterDeletedHistorical
            new Style(Color.BLACK, Color.BRIGHT_BLUE, false, false), // renderSelection
            new Style(Color.BLACK, Color.BRIGHT_YELLOW, false, false), // renderSearchMatch
            new Style(Color.BLACK, Color.BRIGHT_BLUE, false, false), // renderCurrentSearchMatch
            new Style(Color.BLACK, Color.BRIGHT_YELLOW, true, false), // renderBracketMatch
            new Style(Color.BLACK, Color.BRIGHT_YELLOW, true, false), // renderQuoteMatch
            new Style(Color.BRIGHT_WHITE, Color.RED, true, false), // renderUnmatchedBracket
            List.of(
                new Style(Color.BRIGHT_BLUE, Color.DEFAULT, true, false),
                new Style(Color.BRIGHT_CYAN, Color.DEFAULT, true, false),
                new Style(Color.BRIGHT_GREEN, Color.DEFAULT, true, false),
                new Style(Color.BRIGHT_YELLOW, Color.DEFAULT, true, false),
                new Style(Color.BRIGHT_MAGENTA, Color.DEFAULT, true, false),
                new Style(Color.BRIGHT_RED, Color.DEFAULT, true, false)
            ),                                                    // renderRainbowBrackets
            cellStyle(CellColor.BRIGHT_WHITE, CellColor.BLUE, true, false),              // renderTopbar
            cellStyle(CellColor.BLACK, CellColor.BRIGHT_WHITE),             // renderStatus
            cellStyle(CellColor.BLACK, CellColor.BRIGHT_YELLOW),            // renderInteractive
            cellStyle(CellColor.BLACK, CellColor.BRIGHT_GREEN),             // renderNotification
            cellStyle(CellColor.BRIGHT_BLACK, CellColor.DEFAULT),           // renderLeadingWhitespace
            cellStyle(CellColor.BRIGHT_BLACK, CellColor.DEFAULT),           // renderScrollTrack
            cellStyle(CellColor.BRIGHT_WHITE, CellColor.DEFAULT)            // renderScrollThumb
        );
    }

    private static Palette lightPalette() {
        return new Palette(
            cellStyle(CellColor.BRIGHT_WHITE, CellColor.RED),                // promptAlert
            cellStyle(CellColor.BLACK, CellColor.BRIGHT_WHITE, true, false), // topbarFilenameHighlight
            cellStyle(CellColor.BLACK, CellColor.BRIGHT_WHITE),              // menuPopup
            cellStyle(CellColor.BRIGHT_WHITE, CellColor.BLACK),              // menuSelected
            cellStyle(CellColor.BLACK, CellColor.BRIGHT_WHITE),              // menuTopSelected
            cellStyle(CellColor.DEFAULT, CellColor.DEFAULT),                 // menuSelectedMono
            cellStyle(CellColor.BLACK, CellColor.BRIGHT_WHITE),              // tabbar
            cellStyle(CellColor.DEFAULT, CellColor.BRIGHT_WHITE),            // tabbarMono
            cellStyle(CellColor.BRIGHT_WHITE, CellColor.BLACK),              // tabbarActive
            cellStyle(CellColor.DEFAULT, CellColor.DEFAULT),                 // tabbarActiveMono
            cellStyle(CellColor.BLACK, CellColor.BRIGHT_WHITE),              // dialog
            cellStyle(CellColor.BLACK, CellColor.BRIGHT_WHITE, true, false), // dialogBorder
            cellStyle(CellColor.BLACK, CellColor.BRIGHT_WHITE, true, false), // dialogTitle
            cellStyle(CellColor.DEFAULT, CellColor.DEFAULT),                 // dialogSelectedMono
            cellStyle(CellColor.BRIGHT_BLACK, CellColor.BRIGHT_WHITE),       // dialogScrollTrack
            cellStyle(CellColor.BRIGHT_WHITE, CellColor.BLACK),              // dialogScrollThumb
            cellStyle(CellColor.BLACK, CellColor.BRIGHT_WHITE),              // searchBar
            cellStyle(CellColor.BLACK, CellColor.BRIGHT_GREEN),              // notifySuccess
            cellStyle(CellColor.BRIGHT_WHITE, CellColor.BLUE),               // notifyInfo
            cellStyle(CellColor.BLACK, CellColor.BRIGHT_YELLOW),             // notifyWarning
            cellStyle(CellColor.BRIGHT_WHITE, CellColor.RED),                // notifyError
            cellStyle(CellColor.BRIGHT_BLACK, CellColor.BRIGHT_WHITE),       // contextMenuDisabledItem
            cellStyle(CellColor.BLACK, CellColor.BRIGHT_WHITE),              // renderGutter
            cellStyle(CellColor.BRIGHT_WHITE, CellColor.BLACK, true, false), // renderGutterActive
            cellStyle(CellColor.BRIGHT_GREEN, CellColor.BRIGHT_WHITE, true, false), // renderGutterAdded
            cellStyle(CellColor.BRIGHT_YELLOW, CellColor.BRIGHT_WHITE, true, false), // renderGutterModified
            cellStyle(CellColor.BRIGHT_RED, CellColor.BRIGHT_WHITE, true, false), // renderGutterDeleted
            cellStyle(CellColor.BRIGHT_BLACK, CellColor.BRIGHT_WHITE, false, false), // renderGutterAddedHistorical
            cellStyle(CellColor.BRIGHT_BLACK, CellColor.BRIGHT_WHITE, false, false), // renderGutterModifiedHistorical
            cellStyle(CellColor.BRIGHT_BLACK, CellColor.BRIGHT_WHITE, false, false), // renderGutterDeletedHistorical
            new Style(Color.BLACK, Color.BRIGHT_CYAN, false, false), // renderSelection
            new Style(Color.BLACK, Color.BRIGHT_YELLOW, false, false), // renderSearchMatch
            new Style(Color.BLACK, Color.BRIGHT_CYAN, false, false), // renderCurrentSearchMatch
            new Style(Color.BLACK, Color.BRIGHT_YELLOW, true, false), // renderBracketMatch
            new Style(Color.BLACK, Color.BRIGHT_YELLOW, true, false), // renderQuoteMatch
            new Style(Color.BRIGHT_WHITE, Color.RED, true, false), // renderUnmatchedBracket
            List.of(
                new Style(Color.BRIGHT_BLUE, Color.DEFAULT, true, false),
                new Style(Color.BRIGHT_CYAN, Color.DEFAULT, true, false),
                new Style(Color.BRIGHT_GREEN, Color.DEFAULT, true, false),
                new Style(Color.BRIGHT_YELLOW, Color.DEFAULT, true, false),
                new Style(Color.BRIGHT_MAGENTA, Color.DEFAULT, true, false),
                new Style(Color.BRIGHT_RED, Color.DEFAULT, true, false)
            ),                                                       // renderRainbowBrackets
            cellStyle(CellColor.BLACK, CellColor.BRIGHT_WHITE, true, false),              // renderTopbar
            cellStyle(CellColor.BLACK, CellColor.BRIGHT_WHITE),              // renderStatus
            cellStyle(CellColor.BLACK, CellColor.BRIGHT_YELLOW),             // renderInteractive
            cellStyle(CellColor.BLACK, CellColor.BRIGHT_GREEN),              // renderNotification
            cellStyle(CellColor.BRIGHT_BLACK, CellColor.DEFAULT),            // renderLeadingWhitespace
            cellStyle(CellColor.BRIGHT_BLACK, CellColor.DEFAULT),            // renderScrollTrack
            cellStyle(CellColor.BRIGHT_WHITE, CellColor.DEFAULT)             // renderScrollThumb
        );
    }

    private static CellStyle cellStyle(CellColor fg, CellColor bg) {
        return new CellStyle(fg, bg, false, false);
    }

    private static CellStyle cellStyle(CellColor fg, CellColor bg, boolean bold, boolean underline) {
        return new CellStyle(fg, bg, bold, underline);
    }
}
