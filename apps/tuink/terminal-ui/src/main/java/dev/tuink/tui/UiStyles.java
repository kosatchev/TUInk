package dev.tuink.tui;

import dev.tuink.render.Color;
import dev.tuink.render.Style;

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
        Style promptAlert,
        Style topbarFilenameHighlight,
        Style menuPopup,
        Style menuSelected,
        Style menuTopSelected,
        Style menuSelectedMono,
        Style tabbar,
        Style tabbarMono,
        Style tabbarActive,
        Style tabbarActiveMono,
        Style dialog,
        Style dialogBorder,
        Style dialogTitle,
        Style dialogSelectedMono,
        Style dialogScrollTrack,
        Style dialogScrollThumb,
        Style searchBar,
        Style notifySuccess,
        Style notifyInfo,
        Style notifyWarning,
        Style notifyError,
        Style contextMenuDisabledItem,
        Style renderGutter,
        Style renderGutterActive,
        Style renderTopbar,
        Style renderStatus,
        Style renderInteractive,
        Style renderNotification,
        Style renderLeadingWhitespace,
        Style renderScrollTrack,
        Style renderScrollThumb
    ) {
    }

    private static Palette cyanPalette() {
        return new Palette(
            style(Color.BRIGHT_WHITE, Color.RED),               // promptAlert
            style(Color.BRIGHT_WHITE, Color.CYAN, true, false), // topbarFilenameHighlight
            style(Color.BLACK, Color.BRIGHT_WHITE),             // menuPopup
            style(Color.BRIGHT_WHITE, Color.CYAN),              // menuSelected
            style(Color.CYAN, Color.BRIGHT_WHITE),              // menuTopSelected
            style(Color.DEFAULT, Color.DEFAULT),                // menuSelectedMono
            style(Color.BLACK, Color.BRIGHT_WHITE),             // tabbar
            style(Color.DEFAULT, Color.BRIGHT_WHITE),           // tabbarMono
            style(Color.CYAN, Color.DEFAULT),                   // tabbarActive
            style(Color.DEFAULT, Color.DEFAULT),                // tabbarActiveMono
            style(Color.BLACK, Color.BRIGHT_WHITE),             // dialog
            style(Color.BLACK, Color.BRIGHT_WHITE, true, false), // dialogBorder
            style(Color.BRIGHT_WHITE, Color.CYAN, true, false), // dialogTitle
            style(Color.DEFAULT, Color.DEFAULT),                // dialogSelectedMono
            style(Color.BRIGHT_BLACK, Color.BRIGHT_WHITE),      // dialogScrollTrack
            style(Color.BLACK, Color.BRIGHT_YELLOW),            // dialogScrollThumb
            style(Color.BRIGHT_WHITE, Color.CYAN),              // searchBar
            style(Color.BLACK, Color.BRIGHT_GREEN),             // notifySuccess
            style(Color.BRIGHT_WHITE, Color.BLUE),              // notifyInfo
            style(Color.BLACK, Color.BRIGHT_YELLOW),            // notifyWarning
            style(Color.BRIGHT_WHITE, Color.RED),               // notifyError
            style(Color.BRIGHT_BLACK, Color.BRIGHT_WHITE),      // contextMenuDisabledItem
            style(Color.BRIGHT_BLACK, Color.BLACK),             // renderGutter
            style(Color.BRIGHT_WHITE, Color.CYAN, true, false), // renderGutterActive
            style(Color.BRIGHT_WHITE, Color.CYAN, true, false),              // renderTopbar
            style(Color.BLACK, Color.BRIGHT_WHITE),             // renderStatus
            style(Color.BLACK, Color.BRIGHT_YELLOW),            // renderInteractive
            style(Color.BLACK, Color.BRIGHT_GREEN),             // renderNotification
            style(Color.BRIGHT_BLACK, Color.DEFAULT),           // renderLeadingWhitespace
            style(Color.BRIGHT_BLACK, Color.DEFAULT),           // renderScrollTrack
            style(Color.BRIGHT_WHITE, Color.DEFAULT)            // renderScrollThumb
        );
    }

    private static Palette bluePalette() {
        return new Palette(
            style(Color.BRIGHT_WHITE, Color.RED),               // promptAlert
            style(Color.BRIGHT_WHITE, Color.BLUE, true, false), // topbarFilenameHighlight
            style(Color.BLACK, Color.BRIGHT_WHITE),             // menuPopup
            style(Color.BRIGHT_WHITE, Color.BLUE),              // menuSelected
            style(Color.BLUE, Color.BRIGHT_WHITE),              // menuTopSelected
            style(Color.DEFAULT, Color.DEFAULT),                // menuSelectedMono
            style(Color.BLACK, Color.BRIGHT_WHITE),             // tabbar
            style(Color.DEFAULT, Color.BRIGHT_WHITE),           // tabbarMono
            style(Color.BLUE, Color.DEFAULT),                   // tabbarActive
            style(Color.DEFAULT, Color.DEFAULT),                // tabbarActiveMono
            style(Color.BLACK, Color.BRIGHT_WHITE),             // dialog
            style(Color.BLACK, Color.BRIGHT_WHITE, true, false), // dialogBorder
            style(Color.BRIGHT_WHITE, Color.BLUE, true, false), // dialogTitle
            style(Color.DEFAULT, Color.DEFAULT),                // dialogSelectedMono
            style(Color.BRIGHT_BLACK, Color.BRIGHT_WHITE),      // dialogScrollTrack
            style(Color.BLACK, Color.BRIGHT_YELLOW),            // dialogScrollThumb
            style(Color.BRIGHT_WHITE, Color.BLUE),              // searchBar
            style(Color.BLACK, Color.BRIGHT_GREEN),             // notifySuccess
            style(Color.BRIGHT_WHITE, Color.BLUE),              // notifyInfo
            style(Color.BLACK, Color.BRIGHT_YELLOW),            // notifyWarning
            style(Color.BRIGHT_WHITE, Color.RED),               // notifyError
            style(Color.BRIGHT_BLACK, Color.BRIGHT_WHITE),      // contextMenuDisabledItem
            style(Color.BRIGHT_BLACK, Color.BLACK),             // renderGutter
            style(Color.BRIGHT_WHITE, Color.BLUE, true, false), // renderGutterActive
            style(Color.BRIGHT_WHITE, Color.BLUE, true, false),              // renderTopbar
            style(Color.BLACK, Color.BRIGHT_WHITE),             // renderStatus
            style(Color.BLACK, Color.BRIGHT_YELLOW),            // renderInteractive
            style(Color.BLACK, Color.BRIGHT_GREEN),             // renderNotification
            style(Color.BRIGHT_BLACK, Color.DEFAULT),           // renderLeadingWhitespace
            style(Color.BRIGHT_BLACK, Color.DEFAULT),           // renderScrollTrack
            style(Color.BRIGHT_WHITE, Color.DEFAULT)            // renderScrollThumb
        );
    }

    private static Palette lightPalette() {
        return new Palette(
            style(Color.BRIGHT_WHITE, Color.RED),                // promptAlert
            style(Color.BLACK, Color.BRIGHT_WHITE, true, false), // topbarFilenameHighlight
            style(Color.BLACK, Color.BRIGHT_WHITE),              // menuPopup
            style(Color.BRIGHT_WHITE, Color.BLACK),              // menuSelected
            style(Color.BLACK, Color.BRIGHT_WHITE),              // menuTopSelected
            style(Color.DEFAULT, Color.DEFAULT),                 // menuSelectedMono
            style(Color.BLACK, Color.BRIGHT_WHITE),              // tabbar
            style(Color.DEFAULT, Color.BRIGHT_WHITE),            // tabbarMono
            style(Color.BRIGHT_WHITE, Color.BLACK),              // tabbarActive
            style(Color.DEFAULT, Color.DEFAULT),                 // tabbarActiveMono
            style(Color.BLACK, Color.BRIGHT_WHITE),              // dialog
            style(Color.BLACK, Color.BRIGHT_WHITE, true, false), // dialogBorder
            style(Color.BLACK, Color.BRIGHT_WHITE, true, false), // dialogTitle
            style(Color.DEFAULT, Color.DEFAULT),                 // dialogSelectedMono
            style(Color.BRIGHT_BLACK, Color.BRIGHT_WHITE),       // dialogScrollTrack
            style(Color.BRIGHT_WHITE, Color.BLACK),              // dialogScrollThumb
            style(Color.BLACK, Color.BRIGHT_WHITE),              // searchBar
            style(Color.BLACK, Color.BRIGHT_GREEN),              // notifySuccess
            style(Color.BRIGHT_WHITE, Color.BLUE),               // notifyInfo
            style(Color.BLACK, Color.BRIGHT_YELLOW),             // notifyWarning
            style(Color.BRIGHT_WHITE, Color.RED),                // notifyError
            style(Color.BRIGHT_BLACK, Color.BRIGHT_WHITE),       // contextMenuDisabledItem
            style(Color.BLACK, Color.BRIGHT_WHITE),              // renderGutter
            style(Color.BRIGHT_WHITE, Color.BLACK, true, false), // renderGutterActive
            style(Color.BLACK, Color.BRIGHT_WHITE, true, false),              // renderTopbar
            style(Color.BLACK, Color.BRIGHT_WHITE),              // renderStatus
            style(Color.BLACK, Color.BRIGHT_YELLOW),             // renderInteractive
            style(Color.BLACK, Color.BRIGHT_GREEN),              // renderNotification
            style(Color.BRIGHT_BLACK, Color.DEFAULT),            // renderLeadingWhitespace
            style(Color.BRIGHT_BLACK, Color.DEFAULT),            // renderScrollTrack
            style(Color.BRIGHT_WHITE, Color.DEFAULT)             // renderScrollThumb
        );
    }

    private static Style style(Color fg, Color bg) {
        return new Style(fg, bg, false, false);
    }

    private static Style style(Color fg, Color bg, boolean bold, boolean underline) {
        return new Style(fg, bg, bold, underline);
    }
}
