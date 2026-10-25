package dev.tuink.tui;

import static dev.tuink.tui.command.TuinkCommand.MENU_EDIT;
import static dev.tuink.tui.command.TuinkCommand.MENU_FILE;
import static dev.tuink.tui.command.TuinkCommand.MENU_HELP;
import static dev.tuink.tui.command.TuinkCommand.MENU_SETTINGS;
import static dev.tuink.tui.command.TuinkCommand.MENU_TABS;
import static dev.tuink.tui.command.TuinkCommand.MENU_VIEW;

import dev.tuink.core.EditorEngine;
import dev.tuink.core.ClipboardLimitException;
import dev.tuink.core.DocumentEditLimitException;
import dev.tuink.editor.storage.DocumentFileMetadata;
import dev.tuink.core.EditorState;
import dev.tuink.core.GutterDiffMode;
import dev.tuink.core.TabInsertMode;
import dev.tuink.editor.command.CommandInvocation;
import dev.tuink.render.EditorViewNavigation;
import dev.tuink.tui.command.TuinkCommand;
import dev.tuink.tui.ui.ViewportController.CursorPolicy;
import dev.tuink.core.Position;
import java.util.Objects;
import java.util.function.Consumer;

final class InputActionDispatcher {
    private InputActionDispatcher() {
    }

    static DispatchResult dispatch(TuinkCommand command, UiSessionState ui, DispatchContext ctx) {
        DocumentInteraction before = DocumentInteraction.capture(ctx.engine());
        DispatchResult result;
        try {
            result = execute(command, ui, ctx);
        } catch (DocumentEditLimitException limit) {
            result = DispatchResult.handled(limit.getMessage(), StatusLevel.ERROR);
        }
        return result.handled() && before.changed(ctx.engine())
            && result.viewportPolicy() == CursorPolicy.PRESERVE
            ? result.withViewportPolicy(CursorPolicy.FOLLOW_CURSOR) : result;
    }

    private static DispatchResult execute(TuinkCommand command, UiSessionState ui, DispatchContext ctx) {
        if (ui.readingModeSetting && command.editsDocumentText()) {
            return DispatchResult.handled("Reading mode: editing is disabled");
        }
        String statusMessage = null;
        switch (command) {
            case MENU_EDIT -> {
                ctx.topMenu().openFor(CommandInvocation.of(MENU_EDIT.id()));
                return DispatchResult.handled(null);
            }
            case MENU_TABS -> {
                ctx.topMenu().openFor(CommandInvocation.of(MENU_TABS.id()));
                return DispatchResult.handled(null);
            }
            case MENU_VIEW -> {
                ctx.topMenu().openFor(CommandInvocation.of(MENU_VIEW.id()));
                return DispatchResult.handled(null);
            }
            case MENU_HELP -> {
                ctx.topMenu().openFor(CommandInvocation.of(MENU_HELP.id()));
                return DispatchResult.handled(null);
            }
            case MENU_FILE -> {
                ctx.topMenu().openFor(CommandInvocation.of(MENU_FILE.id()));
                return DispatchResult.handled(null);
            }
            case MENU_SETTINGS -> {
                ctx.topMenu().openFor(CommandInvocation.of(MENU_SETTINGS.id()));
                return DispatchResult.handled(null);
            }
            case VIEW_TOGGLE_TAB_BAR -> {
                ui.showTabBarSetting = !ui.showTabBarSetting;
                statusMessage = ui.showTabBarSetting ? "Tab bar shown" : "Tab bar hidden";
                return DispatchResult.handled(statusMessage);
            }
            case VIEW_TOGGLE_LINE_WRAP -> {
                ui.wrapLinesSetting = !ui.wrapLinesSetting;
                if (ui.wrapLinesSetting) {
                    ctx.state().setTopWrapSegment(0);
                    ctx.state().setLeftColumn(0);
                    ctx.state().setLeftVisualColumn(0);
                }
                statusMessage = ui.wrapLinesSetting ? "Wrap lines enabled" : "Wrap lines disabled";
                return DispatchResult.handled(statusMessage);
            }
            case VIEW_TOGGLE_WORD_WRAP -> {
                ui.wrapWordsSetting = !ui.wrapWordsSetting;
                statusMessage = ui.wrapWordsSetting ? "Word wrap enabled" : "Word wrap disabled";
                return DispatchResult.handled(statusMessage);
            }
            case SETTINGS_TOGGLE_READING_MODE -> {
                ui.readingModeSetting = !ui.readingModeSetting;
                statusMessage = ui.readingModeSetting ? "Reading mode enabled" : "Reading mode disabled";
                return DispatchResult.handled(statusMessage);
            }
            case SETTINGS_TOGGLE_CONFIRM_ON_CLOSE -> {
                ui.confirmOnCloseSetting = !ui.confirmOnCloseSetting;
                statusMessage = ui.confirmOnCloseSetting
                    ? "Confirm on close enabled"
                    : "Confirm on close disabled";
                return DispatchResult.handled(statusMessage);
            }
            case SETTINGS_TOGGLE_MOUSE -> {
                if (!ui.mouseModeAvailable) {
                    return DispatchResult.handled(
                        "Mouse mode is unavailable in this terminal",
                        StatusLevel.WARNING
                    );
                }
                ui.mouseModeSetting = !ui.mouseModeSetting;
                ctx.mouseTrackingController().accept(ui.mouseModeSetting);
                if (ui.mouseModeSetting) {
                    return DispatchResult.handled("Mouse mode enabled");
                }
                return DispatchResult.handled(
                    "Mouse mode disabled. Press Alt+S to open Settings and re-enable mouse.",
                    StatusLevel.ERROR
                );
            }
            case VIEW_TOGGLE_ACTIVE_GUTTER -> {
                ui.highlightActiveGutterSetting = !ui.highlightActiveGutterSetting;
                statusMessage = ui.highlightActiveGutterSetting
                    ? "Current line number highlight enabled"
                    : "Current line number highlight disabled";
                return DispatchResult.handled(statusMessage);
            }
            case SETTINGS_TOGGLE_SMART_HOME_END -> {
                ui.smartHomeEndSetting = !ui.smartHomeEndSetting;
                statusMessage = ui.smartHomeEndSetting
                    ? "Smart Home/End enabled"
                    : "Smart Home/End disabled";
                return DispatchResult.handled(statusMessage);
            }
            case SETTINGS_TOGGLE_AUTO_INDENT -> {
                ui.autoIndentSetting = !ui.autoIndentSetting;
                statusMessage = ui.autoIndentSetting
                    ? "Auto indent enabled"
                    : "Auto indent disabled";
                return DispatchResult.handled(statusMessage);
            }
            case SETTINGS_TOGGLE_AUTO_PAIRS -> {
                ui.autoPairsSetting = !ui.autoPairsSetting;
                statusMessage = ui.autoPairsSetting
                    ? "Autopairs enabled"
                    : "Autopairs disabled";
                return DispatchResult.handled(statusMessage);
            }
            case SETTINGS_TOGGLE_AUTO_LIST -> {
                ui.autoListSetting = !ui.autoListSetting;
                statusMessage = ui.autoListSetting
                    ? "Auto list enabled"
                    : "Auto list disabled";
                return DispatchResult.handled(statusMessage);
            }
            case VIEW_TOGGLE_WHITESPACE -> {
                ui.visibleWhitespaceSetting = !ui.visibleWhitespaceSetting;
                statusMessage = ui.visibleWhitespaceSetting
                    ? "Visible whitespace enabled"
                    : "Visible whitespace disabled";
                return DispatchResult.handled(statusMessage);
            }
            case VIEW_TOGGLE_SYNTAX -> {
                if (!ui.syntaxHighlightingAvailable) {
                    return DispatchResult.handled("Syntax highlighting is unavailable in the current theme");
                }
                ui.syntaxHighlightingSetting = !ui.syntaxHighlightingSetting;
                statusMessage = ui.syntaxHighlightingSetting
                    ? "Syntax highlighting enabled"
                    : "Syntax highlighting disabled";
                return DispatchResult.handled(statusMessage);
            }
            case VIEW_TOGGLE_RAINBOW_BRACKETS -> {
                ui.rainbowBracketsSetting = !ui.rainbowBracketsSetting;
                statusMessage = ui.rainbowBracketsSetting
                    ? "Rainbow brackets enabled"
                    : "Rainbow brackets disabled";
                return DispatchResult.handled(statusMessage);
            }
            case VIEW_TOGGLE_SELECTION_HIGHLIGHT -> {
                ui.autoSelectionHighlightSetting = !ui.autoSelectionHighlightSetting;
                statusMessage = ui.autoSelectionHighlightSetting
                    ? "Auto highlight selection enabled"
                    : "Auto highlight selection disabled";
                return DispatchResult.handled(statusMessage);
            }
            case VIEW_CYCLE_GUTTER_DIFF -> {
                ui.gutterDiffModeSetting = nextGutterDiffMode(ui.gutterDiffModeSetting);
                statusMessage = "Gutter diff: " + ui.gutterDiffModeSetting.label();
                return DispatchResult.handled(statusMessage);
            }
            case VIEW_CYCLE_TAB_WIDTH -> {
                ui.tabWidthSetting = ui.tabWidthSetting <= 2 ? 4 : 2;
                statusMessage = ui.tabWidthSetting <= 2
                    ? "Thin tabs enabled (2)"
                    : "Thin tabs disabled (4)";
                return DispatchResult.handled(statusMessage);
            }
            case SETTINGS_CYCLE_TAB_INSERT_MODE -> {
                ui.tabInsertModeSetting = nextTabInsertMode(ui.tabInsertModeSetting);
                statusMessage = "Tab insert mode: " + ui.tabInsertModeSetting.label();
                return DispatchResult.handled(statusMessage);
            }
            case HELP_KEY_HINTS -> {
                ui.helpHintsMode = true;
                return DispatchResult.handled(null);
            }
            case HELP_LICENSES -> {
                ui.helpLicensesMode = true;
                return DispatchResult.handled(null);
            }
            case HELP_AUTHOR -> {
                ui.helpAuthorMode = true;
                return DispatchResult.handled(null);
            }
            case SEARCH_OPEN -> {
                ui.searchMode = true;
                ui.searchEditorFocused = false;
                return DispatchResult.handled(null);
            }
            case EDIT_COPY -> {
                try {
                    if (ctx.engine().hasSelection()) {
                        statusMessage = ctx.engine().copySelection() ? "Copied selection" : "Nothing to copy";
                    } else {
                        statusMessage = ctx.engine().copyCurrentLine() ? "Copied line" : "Nothing to copy";
                    }
                } catch (ClipboardLimitException limit) {
                    return DispatchResult.handled(limit.getMessage(), StatusLevel.ERROR);
                }
                return DispatchResult.handled(statusMessage);
            }
            case EDIT_CUT -> {
                try {
                    if (ctx.engine().hasSelection()) {
                        statusMessage = ctx.engine().cutSelection() ? "Cut selection" : "Nothing to cut";
                    } else {
                        statusMessage = ctx.engine().cutCurrentLine() ? "Cut line" : "Nothing to cut";
                    }
                } catch (ClipboardLimitException limit) {
                    return DispatchResult.handled(limit.getMessage(), StatusLevel.ERROR);
                }
                return DispatchResult.handled(statusMessage);
            }
            case EDIT_PASTE -> {
                try {
                    statusMessage = ctx.engine().pasteClipboard() ? "Pasted" : "Clipboard empty";
                } catch (DocumentEditLimitException limit) {
                    return DispatchResult.handled(limit.getMessage(), StatusLevel.ERROR);
                }
                return DispatchResult.handled(statusMessage);
            }
            case EDIT_UNDO -> {
                DocumentFileMetadata before = ctx.engine().model().fileMetadata();
                statusMessage = ctx.engine().undo()
                    ? historyMessage("Undo", before, ctx.engine().model().fileMetadata()) : "Nothing to undo";
                return DispatchResult.handled(statusMessage);
            }
            case EDIT_REDO -> {
                DocumentFileMetadata before = ctx.engine().model().fileMetadata();
                statusMessage = ctx.engine().redo()
                    ? historyMessage("Redo", before, ctx.engine().model().fileMetadata()) : "Nothing to redo";
                return DispatchResult.handled(statusMessage);
            }
            case EDIT_SELECT_ALL -> {
                statusMessage = ctx.engine().selectAll() ? "Selected all" : "Nothing to select";
                return DispatchResult.handled(statusMessage);
            }
            case EDIT_TOGGLE_LINE_COMMENT -> {
                EditorEngine.LineCommentResult result;
                try {
                    result = ctx.engine().toggleLineComment();
                } catch (DocumentEditLimitException limit) {
                    return DispatchResult.handled("Comment change is too large for available memory. Select fewer lines.",
                        StatusLevel.ERROR);
                }
                return switch (result.action()) {
                    case COMMENTED -> DispatchResult.handled(
                        "Commented " + lineCountLabel(result.lineCount())
                    );
                    case UNCOMMENTED -> DispatchResult.handled(
                        "Uncommented " + lineCountLabel(result.lineCount())
                    );
                    case UNSUPPORTED -> DispatchResult.handled(
                        "Line comments are not supported for this file type",
                        StatusLevel.WARNING
                    );
                    case NO_CONTENT -> DispatchResult.handled(
                        "No non-empty lines to comment",
                        StatusLevel.WARNING
                    );
                };
            }
            case EDIT_INSERT_TAB -> {
                ctx.engine().insertTab();
                return DispatchResult.handled("");
            }
            case CURSOR_UP -> {
                ctx.navigation().moveUp(false, ctx.contentColumns(), ui.wrapLinesSetting, ui.wrapWordsSetting);
                return DispatchResult.followCursor();
            }
            case CURSOR_DOWN -> {
                ctx.navigation().moveDown(false, ctx.contentColumns(), ui.wrapLinesSetting, ui.wrapWordsSetting);
                return DispatchResult.followCursor();
            }
            case CURSOR_LEFT -> {
                ctx.engine().moveLeft();
                return DispatchResult.followCursor();
            }
            case CURSOR_RIGHT -> {
                ctx.engine().moveRight();
                return DispatchResult.followCursor();
            }
            case CURSOR_SELECT_UP -> {
                ctx.navigation().moveUp(true, ctx.contentColumns(), ui.wrapLinesSetting, ui.wrapWordsSetting);
                return DispatchResult.followCursor();
            }
            case CURSOR_SELECT_DOWN -> {
                ctx.navigation().moveDown(true, ctx.contentColumns(), ui.wrapLinesSetting, ui.wrapWordsSetting);
                return DispatchResult.followCursor();
            }
            case CURSOR_SELECT_LEFT -> {
                ctx.engine().moveLeft(true);
                return DispatchResult.followCursor();
            }
            case CURSOR_SELECT_RIGHT -> {
                ctx.engine().moveRight(true);
                return DispatchResult.followCursor();
            }
            case CURSOR_WORD_LEFT -> {
                ctx.engine().moveWordLeft();
                return DispatchResult.followCursor();
            }
            case CURSOR_WORD_RIGHT -> {
                ctx.engine().moveWordRight();
                return DispatchResult.followCursor();
            }
            case CURSOR_PAGE_UP -> {
                ctx.navigation().pageUp(ctx.contentRows(), ctx.contentColumns(), ui.wrapLinesSetting, ui.wrapWordsSetting);
                return DispatchResult.handled(null).withViewportPolicy(ctx.engine().hasSelection()
                    ? CursorPolicy.PAGE_SCROLL : CursorPolicy.FOLLOW_CURSOR);
            }
            case CURSOR_PAGE_DOWN -> {
                ctx.navigation().pageDown(ctx.contentRows(), ctx.contentColumns(), ui.wrapLinesSetting, ui.wrapWordsSetting);
                return DispatchResult.handled(null).withViewportPolicy(ctx.engine().hasSelection()
                    ? CursorPolicy.PAGE_SCROLL : CursorPolicy.FOLLOW_CURSOR);
            }
            case CURSOR_HOME, CURSOR_SELECT_HOME -> {
                boolean selecting = command == TuinkCommand.CURSOR_SELECT_HOME;
                if (ui.smartHomeEndSetting) {
                    ctx.engine().moveHomeSmart(selecting);
                } else {
                    ctx.engine().moveHome(selecting);
                }
                return DispatchResult.followCursor();
            }
            case CURSOR_END, CURSOR_SELECT_END -> {
                boolean selecting = command == TuinkCommand.CURSOR_SELECT_END;
                if (ui.smartHomeEndSetting) {
                    ctx.engine().moveEndSmart(selecting);
                } else {
                    ctx.engine().moveEnd(selecting);
                }
                return DispatchResult.followCursor();
            }
            case CURSOR_DOCUMENT_START, CURSOR_SELECT_DOCUMENT_START -> {
                ctx.engine().moveDocumentStart(command == TuinkCommand.CURSOR_SELECT_DOCUMENT_START);
                return DispatchResult.followCursor();
            }
            case CURSOR_DOCUMENT_END, CURSOR_SELECT_DOCUMENT_END -> {
                ctx.engine().moveDocumentEnd(command == TuinkCommand.CURSOR_SELECT_DOCUMENT_END);
                return DispatchResult.followCursor();
            }
            case EDIT_BACKSPACE -> {
                ctx.engine().backspace();
                return DispatchResult.handled(statusMessage);
            }
            case EDIT_DELETE_FORWARD -> {
                ctx.engine().deleteForward();
                return DispatchResult.handled(statusMessage);
            }
            case EDIT_INSERT_NEWLINE -> {
                ctx.engine().insertNewline();
                return DispatchResult.handled(statusMessage);
            }
            case UI_ESCAPE, SEARCH_REPLACE -> {
                return DispatchResult.handled(null);
            }
            default -> {
                return DispatchResult.notHandled();
            }
        }
    }

    private static TabInsertMode nextTabInsertMode(TabInsertMode mode) {
        return mode == null ? TabInsertMode.AUTO : mode.next();
    }

    private static String historyMessage(String action, DocumentFileMetadata before, DocumentFileMetadata after) {
        return before.charset().equals(after.charset()) && before.bomKind() == after.bomKind()
            ? action : action + ": encoding restored to " + after.charset().displayName()
                + (after.bomKind() == dev.tuink.editor.storage.BomKind.NONE ? " without BOM" : " with BOM");
    }

    private static GutterDiffMode nextGutterDiffMode(GutterDiffMode mode) {
        return mode == null ? GutterDiffMode.MYERS : mode.next();
    }

    private static String lineCountLabel(int count) {
        return count + (count == 1 ? " line" : " lines");
    }

    record DispatchContext(
        EditorEngine engine,
        EditorState state,
        TopMenuController topMenu,
        Consumer<Boolean> mouseTrackingController,
        int contentRows,
        int contentColumns,
        EditorViewNavigation navigation
    ) {
        DispatchContext {
            if (mouseTrackingController == null) {
                throw new IllegalArgumentException("mouseTrackingController must not be null");
            }
            if (engine == null ? navigation != null || state != null
                : navigation == null || navigation.engine() != engine || engine.state() != state) {
                throw new IllegalArgumentException("navigation must belong to the context's editor view");
            }
        }
    }

    enum StatusLevel {
        INFO,
        WARNING,
        ERROR
    }

    /** Result of the selected recipient; viewport policy follows executed effects. */
    record DispatchResult(boolean handled, String statusMessage, StatusLevel statusLevel, CursorPolicy viewportPolicy) {
        static DispatchResult handled(String statusMessage) {
            return handled(statusMessage, StatusLevel.INFO);
        }

        static DispatchResult handled(String statusMessage, StatusLevel statusLevel) {
            return new DispatchResult(true, statusMessage, statusLevel, CursorPolicy.PRESERVE);
        }

        static DispatchResult followCursor() {
            return handled(null).withViewportPolicy(CursorPolicy.FOLLOW_CURSOR);
        }

        DispatchResult withViewportPolicy(CursorPolicy policy) {
            return new DispatchResult(handled, statusMessage, statusLevel, Objects.requireNonNull(policy));
        }

        static DispatchResult notHandled() {
            return new DispatchResult(false, null, StatusLevel.INFO, CursorPolicy.PRESERVE);
        }
    }

    private record DocumentInteraction(long revision, Position cursor, Position selection) {
        static DocumentInteraction capture(EditorEngine engine) {
            return engine == null ? new DocumentInteraction(-1, null, null)
                : new DocumentInteraction(engine.model().revision(), engine.state().getCursor(),
                    engine.state().getSelectionAnchor());
        }

        boolean changed(EditorEngine engine) {
            return engine != null && (revision != engine.model().revision()
                || !Objects.equals(cursor, engine.state().getCursor())
                || !Objects.equals(selection, engine.state().getSelectionAnchor()));
        }
    }
}
