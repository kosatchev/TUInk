package dev.tuink.tui.style;

import dev.tuink.render.Color;
import dev.tuink.render.Style;
import dev.tuiframe.style.CellColor;
import dev.tuiframe.style.CellStyle;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.EnumMap;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import dev.tuink.editor.highlight.SemanticStyleKey;
import dev.tuink.render.SyntaxStyleResolver;
import dev.tuink.render.markdown.MarkdownLayout;
import java.util.Locale;

/**
 * Centralized style palette for TUI widgets and renderer defaults.
 */
public final class UiStyles {
    private UiStyles() {
    }

    private static final Palette CYAN = palette(Theme.CYAN);
    private static final Palette BLUE = palette(Theme.BLUE);
    private static final Palette MONOCHROME = palette(Theme.MONOCHROME);

    public static Palette forTheme(Theme theme) {
        return switch (theme) {
            case BLUE -> BLUE;
            case CYAN -> CYAN;
            case MONOCHROME -> MONOCHROME;
        };
    }

    public enum Theme {
        CYAN("Cyan"),
        BLUE("Blue"),
        MONOCHROME("Monochrome");

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
                case BLUE -> MONOCHROME;
                case MONOCHROME -> CYAN;
            };
        }

        public static Theme parse(String raw) {
            if (raw == null) {
                return CYAN;
            }
            return switch (raw.trim().toLowerCase(Locale.ROOT)) {
                case "cyan" -> CYAN;
                case "blue" -> BLUE;
                case "monochrome" -> MONOCHROME;
                default -> throw new IllegalArgumentException("Unknown theme: " + raw + " (expected: blue, cyan, monochrome)");
            };
        }
    }

    /** Independent immutable style groups, with exact identity for prepared-row caches. */
    public static final class Palette {
        private static final AtomicLong NEXT_ID = new AtomicLong(1);
        private final InterfaceStyles windows;
        private final PreviewStyles preview;
        private final SyntaxStyles syntax;
        private final long identity;

        public Palette(InterfaceStyles windows, PreviewStyles preview, SyntaxStyles syntax) {
            this.windows = Objects.requireNonNull(windows);
            this.preview = Objects.requireNonNull(preview);
            this.syntax = Objects.requireNonNull(syntax);
            identity = NEXT_ID.getAndIncrement();
            if (identity <= 0 || identity > (Long.MAX_VALUE >>> 1))
                throw new IllegalStateException("Style identity exhausted");
        }
        public InterfaceStyles windows() { return windows; }
        public PreviewStyles preview() { return preview; }
        public SyntaxStyles syntax() { return syntax; }
        public long identity() { return identity; }
    }

    public record PreviewStyles(Style body, Style codeBody, Map<MarkdownLayout.Role, Style> roles) {
        public PreviewStyles {
            Objects.requireNonNull(body);
            Objects.requireNonNull(codeBody);
            roles = Map.copyOf(roles);
        }
        public Style role(MarkdownLayout.Role role) { return roles.getOrDefault(role, Style.DEFAULT); }
    }

    /** Shared token palette for Source and code inside Preview. */
    public record SyntaxStyles(Map<SemanticStyleKey, Style> roles, Style plain, Style bracketMatch,
        Style quoteMatch, Style unmatchedBracket, List<Style> bracketDepths) implements SyntaxStyleResolver {
        public SyntaxStyles {
            roles = Map.copyOf(roles);
            Objects.requireNonNull(plain);
            Objects.requireNonNull(bracketMatch);
            Objects.requireNonNull(quoteMatch);
            Objects.requireNonNull(unmatchedBracket);
            bracketDepths = List.copyOf(bracketDepths);
            if (bracketDepths.isEmpty()) throw new IllegalArgumentException("Empty bracket palette");
        }
        @Override public Style resolve(SemanticStyleKey key) { return roles.getOrDefault(key, plain); }
    }

    /** Window controls and shared document interaction overlays. */
    public record InterfaceStyles(
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
        CellStyle renderTopbar,
        CellStyle renderStatus,
        CellStyle renderInteractive,
        CellStyle renderNotification,
        CellStyle renderLeadingWhitespace,
        CellStyle renderBody
    ) {
        public CellStyle renderBorder() {
            return renderGutter;
        }
    }

    private static Palette palette(Theme theme) {
        return new Palette(switch (theme) {
            case CYAN -> cyanWindows();
            case BLUE -> blueWindows();
            case MONOCHROME -> monochromeWindows();
        }, previewStyles(theme), syntaxStyles(theme));
    }

    private static PreviewStyles previewStyles(Theme theme) {
        boolean gray = theme == Theme.MONOCHROME;
        Map<MarkdownLayout.Role, Style> roles = new EnumMap<>(MarkdownLayout.Role.class);
        roles.put(MarkdownLayout.Role.HEADING, new Style(gray ? Color.GRAY_238 : Color.CYAN, Color.DEFAULT, true, false));
        roles.put(MarkdownLayout.Role.STRONG, new Style(gray ? Color.GRAY_218 : Color.DEFAULT, Color.DEFAULT, true, false));
        roles.put(MarkdownLayout.Role.EMPHASIS, new Style(Color.DEFAULT, Color.DEFAULT, false, true));
        roles.put(MarkdownLayout.Role.LINK, new Style(gray ? Color.GRAY_218 : Color.CYAN, Color.DEFAULT, false, true));
        roles.put(MarkdownLayout.Role.QUOTE_MARKER, Style.fg(gray ? Color.GRAY_188 : Color.CYAN));
        roles.put(MarkdownLayout.Role.QUOTE, Style.fg(gray ? Color.GRAY_158 : Color.BRIGHT_BLACK));
        roles.put(MarkdownLayout.Role.CODE, Style.fg(gray ? Color.GRAY_218 : Color.YELLOW));
        roles.put(MarkdownLayout.Role.MATH, Style.fg(gray ? Color.GRAY_218 : Color.GREEN));
        roles.put(MarkdownLayout.Role.DIAGNOSTIC, new Style(gray ? Color.GRAY_238 : Color.RED, Color.DEFAULT, gray, gray));
        return new PreviewStyles(gray ? new Style(Color.GRAY_188, Color.GRAY_028, false, false) : Style.DEFAULT,
            new Style(gray ? Color.GRAY_218 : Color.WHITE, Color.DARK_GRAY, false, false), roles);
    }

    private static SyntaxStyles syntaxStyles(Theme theme) {
        Map<SemanticStyleKey, Style> roles = new HashMap<>();
        if (theme == Theme.MONOCHROME) {
            roles.put(SemanticStyleKey.KEYWORD, new Style(Color.GRAY_218, Color.DEFAULT, true, false));
            roles.put(SemanticStyleKey.TYPE, Style.fg(Color.GRAY_238));
            roles.put(SemanticStyleKey.NUMBER, Style.fg(Color.GRAY_218));
            roles.put(SemanticStyleKey.STRING, Style.fg(Color.GRAY_188));
            roles.put(SemanticStyleKey.COMMENT, Style.fg(Color.GRAY_158));
            roles.put(SemanticStyleKey.DECORATOR, Style.fg(Color.GRAY_238));
            roles.put(SemanticStyleKey.MARKUP, new Style(Color.GRAY_218, Color.DEFAULT, true, false));
            roles.put(SemanticStyleKey.LINK, Style.fg(Color.GRAY_218));
            roles.put(SemanticStyleKey.QUOTE, Style.fg(Color.GRAY_158));
            roles.put(SemanticStyleKey.CODE, Style.fg(Color.GRAY_218));
            Style pair = new Style(Color.GRAY_238, Color.DEFAULT, true, true);
            return new SyntaxStyles(roles, Style.fg(Color.GRAY_188), pair, pair,
                new Style(Color.GRAY_028, Color.GRAY_218, true, false),
                List.of(Style.fg(Color.GRAY_158), Style.fg(Color.GRAY_188),
                    Style.fg(Color.GRAY_218), Style.fg(Color.GRAY_238)));
        }
        var standard = SyntaxStyleResolver.standard();
        for (var key : List.of(SemanticStyleKey.KEYWORD, SemanticStyleKey.TYPE, SemanticStyleKey.NUMBER,
            SemanticStyleKey.STRING, SemanticStyleKey.COMMENT, SemanticStyleKey.DECORATOR,
            SemanticStyleKey.MARKUP, SemanticStyleKey.LINK, SemanticStyleKey.QUOTE, SemanticStyleKey.CODE))
            roles.put(key, standard.resolve(key));
        Style pair = new Style(Color.BLACK, Color.BRIGHT_YELLOW, true, false);
        return new SyntaxStyles(roles, Style.DEFAULT, pair, pair,
            new Style(Color.BRIGHT_WHITE, Color.RED, true, false),
            List.of(new Style(Color.BRIGHT_BLUE, Color.DEFAULT, true, false),
                new Style(Color.BRIGHT_CYAN, Color.DEFAULT, true, false),
                new Style(Color.BRIGHT_GREEN, Color.DEFAULT, true, false),
                new Style(Color.BRIGHT_YELLOW, Color.DEFAULT, true, false),
                new Style(Color.BRIGHT_MAGENTA, Color.DEFAULT, true, false),
                new Style(Color.BRIGHT_RED, Color.DEFAULT, true, false)));
    }

    private static InterfaceStyles cyanWindows() {
        return new InterfaceStyles(
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
            cellStyle(CellColor.BRIGHT_WHITE, CellColor.CYAN, true, false),              // renderTopbar
            cellStyle(CellColor.BLACK, CellColor.BRIGHT_WHITE),             // renderStatus
            cellStyle(CellColor.BLACK, CellColor.BRIGHT_YELLOW),            // renderInteractive
            cellStyle(CellColor.BLACK, CellColor.BRIGHT_GREEN),             // renderNotification
            cellStyle(CellColor.BRIGHT_BLACK, CellColor.DEFAULT),            // renderLeadingWhitespace
            CellStyle.DEFAULT // renderBody
        );
    }

    private static InterfaceStyles blueWindows() {
        return new InterfaceStyles(
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
            cellStyle(CellColor.BRIGHT_WHITE, CellColor.BLUE, true, false),              // renderTopbar
            cellStyle(CellColor.BLACK, CellColor.BRIGHT_WHITE),             // renderStatus
            cellStyle(CellColor.BLACK, CellColor.BRIGHT_YELLOW),            // renderInteractive
            cellStyle(CellColor.BLACK, CellColor.BRIGHT_GREEN),             // renderNotification
            cellStyle(CellColor.BRIGHT_BLACK, CellColor.DEFAULT),            // renderLeadingWhitespace
            CellStyle.DEFAULT // renderBody
        );
    }

    /** Monochrome palette: semantic decoration capabilities are resolved separately. */
    private static InterfaceStyles monochromeWindows() {
        return new InterfaceStyles(
            cellStyle(CellColor.BRIGHT_WHITE, CellColor.BLACK, true, true),  // promptAlert
            cellStyle(CellColor.BLACK, CellColor.BRIGHT_WHITE, true, false), // topbarFilenameHighlight
            cellStyle(CellColor.BLACK, CellColor.BRIGHT_WHITE),              // menuPopup
            cellStyle(CellColor.BRIGHT_WHITE, CellColor.BLACK),              // menuSelected
            cellStyle(CellColor.BLACK, CellColor.BRIGHT_WHITE),              // menuTopSelected
            cellStyle(CellColor.GRAY_218, CellColor.DEFAULT, true, true),     // menuSelectedMono
            cellStyle(CellColor.BLACK, CellColor.BRIGHT_WHITE),              // tabbar
            cellStyle(CellColor.DEFAULT, CellColor.BRIGHT_WHITE),            // tabbarMono
            cellStyle(CellColor.BRIGHT_WHITE, CellColor.BLACK),              // tabbarActive
            cellStyle(CellColor.GRAY_218, CellColor.DEFAULT, true, true),     // tabbarActiveMono
            cellStyle(CellColor.BLACK, CellColor.BRIGHT_WHITE),              // dialog
            cellStyle(CellColor.BLACK, CellColor.BRIGHT_WHITE, true, false), // dialogBorder
            cellStyle(CellColor.BLACK, CellColor.BRIGHT_WHITE, true, false), // dialogTitle
            cellStyle(CellColor.GRAY_218, CellColor.DEFAULT, true, true),     // dialogSelectedMono
            cellStyle(CellColor.BLACK, CellColor.BRIGHT_WHITE),              // searchBar
            cellStyle(CellColor.BLACK, CellColor.BRIGHT_WHITE, true, false), // notifySuccess
            cellStyle(CellColor.BLACK, CellColor.BRIGHT_WHITE),              // notifyInfo
            cellStyle(CellColor.BLACK, CellColor.BRIGHT_WHITE, true, true),  // notifyWarning
            cellStyle(CellColor.BRIGHT_WHITE, CellColor.BLACK, true, true),  // notifyError
            cellStyle(CellColor.BRIGHT_BLACK, CellColor.BRIGHT_WHITE),       // contextMenuDisabledItem
            cellStyle(CellColor.BLACK, CellColor.BRIGHT_WHITE),              // renderGutter
            cellStyle(CellColor.BRIGHT_WHITE, CellColor.BLACK, true, false), // renderGutterActive
            cellStyle(CellColor.BLACK, CellColor.BRIGHT_WHITE, true, false), // renderGutterAdded
            cellStyle(CellColor.BLACK, CellColor.BRIGHT_WHITE, false, true), // renderGutterModified
            cellStyle(CellColor.BRIGHT_WHITE, CellColor.BLACK, true, false), // renderGutterDeleted
            cellStyle(CellColor.BRIGHT_BLACK, CellColor.BRIGHT_WHITE),       // renderGutterAddedHistorical
            cellStyle(CellColor.BRIGHT_BLACK, CellColor.BRIGHT_WHITE),       // renderGutterModifiedHistorical
            cellStyle(CellColor.BRIGHT_BLACK, CellColor.BRIGHT_WHITE),       // renderGutterDeletedHistorical
            new Style(Color.BRIGHT_WHITE, Color.BLACK, true, false),        // renderSelection
            new Style(Color.BLACK, Color.BRIGHT_WHITE, true, true),          // renderSearchMatch
            new Style(Color.BRIGHT_WHITE, Color.BLACK, true, true),         // renderCurrentSearchMatch
            cellStyle(CellColor.BLACK, CellColor.BRIGHT_WHITE, true, false), // renderTopbar
            cellStyle(CellColor.BLACK, CellColor.BRIGHT_WHITE),              // renderStatus
            cellStyle(CellColor.BLACK, CellColor.BRIGHT_WHITE, true, false), // renderInteractive
            cellStyle(CellColor.BLACK, CellColor.BRIGHT_WHITE, true, false), // renderNotification
            cellStyle(CellColor.BRIGHT_BLACK, CellColor.DEFAULT),             // renderLeadingWhitespace
            cellStyle(CellColor.GRAY_188, CellColor.GRAY_028) // renderBody
        );
    }

    private static CellStyle cellStyle(CellColor fg, CellColor bg) {
        return new CellStyle(fg, bg, false, false);
    }

    private static CellStyle cellStyle(CellColor fg, CellColor bg, boolean bold, boolean underline) {
        return new CellStyle(fg, bg, bold, underline);
    }
}
