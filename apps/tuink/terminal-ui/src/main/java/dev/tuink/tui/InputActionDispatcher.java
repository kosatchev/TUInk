package dev.tuink.tui;

import static dev.tuink.tui.command.TuinkCommand.MENU_EDIT;
import static dev.tuink.tui.command.TuinkCommand.MENU_FILE;
import static dev.tuink.tui.command.TuinkCommand.MENU_HELP;
import static dev.tuink.tui.command.TuinkCommand.MENU_SETTINGS;
import static dev.tuink.tui.command.TuinkCommand.MENU_TABS;
import static dev.tuink.tui.command.TuinkCommand.MENU_VIEW;

import dev.tuink.core.EditorEngine;
import dev.tuink.core.EditorState;
import dev.tuink.core.GutterDiffMode;
import dev.tuink.core.TabInsertMode;
import dev.tuink.editor.command.CommandInvocation;
import dev.tuink.tui.command.TuinkCommand;
import java.util.function.Consumer;

final class InputActionDispatcher {
    private InputActionDispatcher() {
    }

    static DispatchResult dispatch(TuinkCommand command, UiSessionState ui, DispatchContext ctx) {
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
                return DispatchResult.handled(null);
            }
            case EDIT_COPY -> {
                if (ctx.engine().hasSelection()) {
                    statusMessage = ctx.engine().copySelection() ? "Copied selection" : "Nothing to copy";
                } else {
                    statusMessage = ctx.engine().copyCurrentLine() ? "Copied line" : "Nothing to copy";
                }
                return DispatchResult.handled(statusMessage);
            }
            case EDIT_CUT -> {
                if (ui.readingModeSetting) {
                    statusMessage = "Reading mode: editing is disabled";
                } else {
                    if (ctx.engine().hasSelection()) {
                        statusMessage = ctx.engine().cutSelection() ? "Cut selection" : "Nothing to cut";
                    } else {
                        statusMessage = ctx.engine().cutCurrentLine() ? "Cut line" : "Nothing to cut";
                    }
                }
                return DispatchResult.handled(statusMessage);
            }
            case EDIT_PASTE -> {
                if (ui.readingModeSetting) {
                    statusMessage = "Reading mode: editing is disabled";
                } else {
                    statusMessage = ctx.engine().pasteClipboard() ? "Pasted" : "Clipboard empty";
                }
                return DispatchResult.handled(statusMessage);
            }
            case EDIT_UNDO -> {
                if (ui.readingModeSetting) {
                    return DispatchResult.handled("Reading mode: editing is disabled");
                }
                statusMessage = ctx.engine().undo() ? "Undo" : "Nothing to undo";
                return DispatchResult.handled(statusMessage);
            }
            case EDIT_REDO -> {
                if (ui.readingModeSetting) {
                    return DispatchResult.handled("Reading mode: editing is disabled");
                }
                statusMessage = ctx.engine().redo() ? "Redo" : "Nothing to redo";
                return DispatchResult.handled(statusMessage);
            }
            case EDIT_SELECT_ALL -> {
                statusMessage = ctx.engine().selectAll() ? "Selected all" : "Nothing to select";
                return DispatchResult.handled(statusMessage);
            }
            case EDIT_TOGGLE_LINE_COMMENT -> {
                if (ui.readingModeSetting) {
                    return DispatchResult.handled("Reading mode: editing is disabled");
                }
                EditorEngine.LineCommentResult result = ctx.engine().toggleLineComment();
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
                if (ui.readingModeSetting) {
                    statusMessage = "Reading mode: editing is disabled";
                } else {
                    ctx.engine().insertTab();
                    statusMessage = "";
                }
                return DispatchResult.handled(statusMessage);
            }
            case CURSOR_UP -> {
                ctx.engine().moveUp(false, ctx.contentColumns(), ui.wrapLinesSetting, ui.wrapWordsSetting);
                return DispatchResult.handled(null);
            }
            case CURSOR_DOWN -> {
                ctx.engine().moveDown(false, ctx.contentColumns(), ui.wrapLinesSetting, ui.wrapWordsSetting);
                return DispatchResult.handled(null);
            }
            case CURSOR_LEFT -> {
                ctx.engine().moveLeft();
                return DispatchResult.handled(null);
            }
            case CURSOR_RIGHT -> {
                ctx.engine().moveRight();
                return DispatchResult.handled(null);
            }
            case CURSOR_SELECT_UP -> {
                ctx.engine().moveUp(true, ctx.contentColumns(), ui.wrapLinesSetting, ui.wrapWordsSetting);
                return DispatchResult.handled(null);
            }
            case CURSOR_SELECT_DOWN -> {
                ctx.engine().moveDown(true, ctx.contentColumns(), ui.wrapLinesSetting, ui.wrapWordsSetting);
                return DispatchResult.handled(null);
            }
            case CURSOR_SELECT_LEFT -> {
                ctx.engine().moveLeft(true);
                return DispatchResult.handled(null);
            }
            case CURSOR_SELECT_RIGHT -> {
                ctx.engine().moveRight(true);
                return DispatchResult.handled(null);
            }
            case CURSOR_WORD_LEFT -> {
                ctx.engine().moveWordLeft();
                return DispatchResult.handled(null);
            }
            case CURSOR_WORD_RIGHT -> {
                ctx.engine().moveWordRight();
                return DispatchResult.handled(null);
            }
            case CURSOR_PAGE_UP -> {
                ctx.engine().pageUp(ctx.contentRows(), ctx.contentColumns(), ui.wrapLinesSetting, ui.wrapWordsSetting);
                return DispatchResult.handled(null);
            }
            case CURSOR_PAGE_DOWN -> {
                ctx.engine().pageDown(ctx.contentRows(), ctx.contentColumns(), ui.wrapLinesSetting, ui.wrapWordsSetting);
                return DispatchResult.handled(null);
            }
            case CURSOR_HOME -> {
                if (ui.smartHomeEndSetting) {
                    ctx.engine().moveHomeSmart();
                } else {
                    ctx.engine().moveHome();
                }
                return DispatchResult.handled(null);
            }
            case CURSOR_END -> {
                if (ui.smartHomeEndSetting) {
                    ctx.engine().moveEndSmart();
                } else {
                    ctx.engine().moveEnd();
                }
                return DispatchResult.handled(null);
            }
            case EDIT_BACKSPACE -> {
                if (ui.readingModeSetting) {
                    statusMessage = "Reading mode: editing is disabled";
                } else {
                    ctx.engine().backspace();
                }
                return DispatchResult.handled(statusMessage);
            }
            case EDIT_DELETE_FORWARD -> {
                if (ui.readingModeSetting) {
                    statusMessage = "Reading mode: editing is disabled";
                } else {
                    ctx.engine().deleteForward();
                }
                return DispatchResult.handled(statusMessage);
            }
            case EDIT_INSERT_NEWLINE -> {
                if (ui.readingModeSetting) {
                    statusMessage = "Reading mode: editing is disabled";
                } else {
                    ctx.engine().insertNewline();
                }
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
        int contentColumns
    ) {
        DispatchContext {
            if (mouseTrackingController == null) {
                throw new IllegalArgumentException("mouseTrackingController must not be null");
            }
        }
    }

    enum StatusLevel {
        INFO,
        WARNING,
        ERROR
    }

    record DispatchResult(boolean handled, String statusMessage, StatusLevel statusLevel) {
        static DispatchResult handled(String statusMessage) {
            return handled(statusMessage, StatusLevel.INFO);
        }

        static DispatchResult handled(String statusMessage, StatusLevel statusLevel) {
            return new DispatchResult(true, statusMessage, statusLevel);
        }

        static DispatchResult notHandled() {
            return new DispatchResult(false, null, StatusLevel.INFO);
        }
    }
}
