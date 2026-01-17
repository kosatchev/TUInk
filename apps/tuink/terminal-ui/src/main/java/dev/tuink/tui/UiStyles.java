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
            case CLASSIC -> bluePalette();
            case CYAN -> cyanPalette();
            case LIGHT -> lightPalette();
        };
    }

    public enum Theme {
        CLASSIC("Blue"),
        CYAN("Cyan"),
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
                case CLASSIC -> CYAN;
                case CYAN -> LIGHT;
                case LIGHT -> CLASSIC;
            };
        }

        public static Theme parse(String raw) {
            if (raw == null) {
                return CLASSIC;
            }
            return switch (raw.trim().toLowerCase()) {
                case "classic" -> CLASSIC;
                case "light" -> LIGHT;
                default -> throw new IllegalArgumentException("Unknown theme: " + raw + " (expected: classic, light)");
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

    private static Palette bluePalette() {
        return new Palette(
            style(Color.BRIGHT_WHITE, Color.RED),
            style(Color.BRIGHT_WHITE, Color.BLUE, true, false),
            style(Color.BLACK, Color.BRIGHT_WHITE),
            style(Color.BRIGHT_WHITE, Color.BLUE),
            style(Color.BLUE, Color.BRIGHT_WHITE),
            style(Color.DEFAULT, Color.DEFAULT),
            style(Color.BLACK, Color.BRIGHT_WHITE),
            style(Color.DEFAULT, Color.BRIGHT_WHITE),
            style(Color.BLUE, Color.DEFAULT),
            style(Color.DEFAULT, Color.DEFAULT),
            style(Color.BLACK, Color.BRIGHT_WHITE),
            style(Color.BLACK, Color.BRIGHT_WHITE, true, false),
            style(Color.BRIGHT_WHITE, Color.BLUE, true, false),
            style(Color.DEFAULT, Color.DEFAULT),
            style(Color.BRIGHT_BLACK, Color.BRIGHT_WHITE),
            style(Color.BLACK, Color.BRIGHT_YELLOW),
            style(Color.BRIGHT_WHITE, Color.BLUE),
            style(Color.BLACK, Color.BRIGHT_GREEN),
            style(Color.BRIGHT_WHITE, Color.BLUE),
            style(Color.BLACK, Color.BRIGHT_YELLOW),
            style(Color.BRIGHT_WHITE, Color.RED),
            style(Color.BRIGHT_BLACK, Color.BRIGHT_WHITE),
            style(Color.BRIGHT_BLACK, Color.BLACK),
            style(Color.BRIGHT_WHITE, Color.BLUE, true, false),
            style(Color.BRIGHT_WHITE, Color.BLUE),
            style(Color.BLACK, Color.BRIGHT_WHITE),
            style(Color.BLACK, Color.BRIGHT_YELLOW),
            style(Color.BLACK, Color.BRIGHT_GREEN),
            style(Color.BRIGHT_BLACK, Color.DEFAULT),
            style(Color.BRIGHT_BLACK, Color.DEFAULT),
            style(Color.BRIGHT_WHITE, Color.DEFAULT)
        );
    }

    private static Palette cyanPalette() {
        return new Palette(
            style(Color.BRIGHT_WHITE, Color.RED),
            style(Color.BRIGHT_WHITE, Color.CYAN, true, false),
            style(Color.BLACK, Color.BRIGHT_WHITE),
            style(Color.BRIGHT_WHITE, Color.CYAN),
            style(Color.CYAN, Color.BRIGHT_WHITE),
            style(Color.DEFAULT, Color.DEFAULT),
            style(Color.BLACK, Color.BRIGHT_WHITE),
            style(Color.DEFAULT, Color.BRIGHT_WHITE),
            style(Color.CYAN, Color.DEFAULT),
            style(Color.DEFAULT, Color.DEFAULT),
            style(Color.BLACK, Color.BRIGHT_WHITE),
            style(Color.BLACK, Color.BRIGHT_WHITE, true, false),
            style(Color.BRIGHT_WHITE, Color.CYAN, true, false),
            style(Color.DEFAULT, Color.DEFAULT),
            style(Color.BRIGHT_BLACK, Color.BRIGHT_WHITE),
            style(Color.BLACK, Color.BRIGHT_YELLOW),
            style(Color.BRIGHT_WHITE, Color.CYAN),
            style(Color.BLACK, Color.BRIGHT_GREEN),
            style(Color.BRIGHT_WHITE, Color.CYAN),
            style(Color.BLACK, Color.BRIGHT_YELLOW),
            style(Color.BRIGHT_WHITE, Color.RED),
            style(Color.BRIGHT_BLACK, Color.BRIGHT_WHITE),
            style(Color.BRIGHT_BLACK, Color.BLACK),
            style(Color.BRIGHT_WHITE, Color.CYAN, true, false),
            style(Color.BRIGHT_WHITE, Color.CYAN),
            style(Color.BLACK, Color.BRIGHT_WHITE),
            style(Color.BLACK, Color.BRIGHT_YELLOW),
            style(Color.BLACK, Color.BRIGHT_GREEN),
            style(Color.BRIGHT_BLACK, Color.DEFAULT),
            style(Color.BRIGHT_BLACK, Color.DEFAULT),
            style(Color.BRIGHT_WHITE, Color.DEFAULT)
        );
    }

    private static Palette lightPalette() {
        return new Palette(
            style(Color.BRIGHT_WHITE, Color.RED),
            style(Color.BLACK, Color.BRIGHT_WHITE, true, false),
            style(Color.BLACK, Color.BRIGHT_WHITE),
            style(Color.BRIGHT_WHITE, Color.BLACK),
            style(Color.BLACK, Color.BRIGHT_WHITE),
            style(Color.DEFAULT, Color.DEFAULT),
            style(Color.BLACK, Color.BRIGHT_WHITE),
            style(Color.DEFAULT, Color.BRIGHT_WHITE),
            style(Color.BRIGHT_WHITE, Color.BLACK),
            style(Color.DEFAULT, Color.DEFAULT),
            style(Color.BLACK, Color.BRIGHT_WHITE),
            style(Color.BLACK, Color.BRIGHT_WHITE, true, false),
            style(Color.BLACK, Color.BRIGHT_WHITE, true, false),
            style(Color.DEFAULT, Color.DEFAULT),
            style(Color.BRIGHT_BLACK, Color.BRIGHT_WHITE),
            style(Color.BRIGHT_WHITE, Color.BLACK),
            style(Color.BLACK, Color.BRIGHT_WHITE),
            style(Color.BLACK, Color.BRIGHT_GREEN),
            style(Color.BLACK, Color.BRIGHT_WHITE),
            style(Color.BLACK, Color.BRIGHT_YELLOW),
            style(Color.BRIGHT_WHITE, Color.RED),
            style(Color.BRIGHT_BLACK, Color.BRIGHT_WHITE),
            style(Color.BRIGHT_BLACK, Color.BRIGHT_WHITE),
            style(Color.BLACK, Color.BRIGHT_WHITE, true, false),
            style(Color.BLACK, Color.BRIGHT_WHITE),
            style(Color.BLACK, Color.BRIGHT_WHITE),
            style(Color.BLACK, Color.BRIGHT_YELLOW),
            style(Color.BLACK, Color.BRIGHT_GREEN),
            style(Color.BRIGHT_BLACK, Color.DEFAULT),
            style(Color.BRIGHT_BLACK, Color.DEFAULT),
            style(Color.BLACK, Color.DEFAULT)
        );
    }

    private static Style style(Color fg, Color bg) {
        return new Style(fg, bg, false, false);
    }

    private static Style style(Color fg, Color bg, boolean bold, boolean underline) {
        return new Style(fg, bg, bold, underline);
    }
}
