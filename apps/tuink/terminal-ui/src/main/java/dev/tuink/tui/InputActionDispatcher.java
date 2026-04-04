package dev.tuink.tui;

import dev.tuink.core.EditorEngine;
import dev.tuink.core.EditorState;
import dev.tuink.core.GutterDiffMode;
import dev.tuink.core.TabInsertMode;
import dev.tuink.tui.flow.SearchFocus;
import dev.tuink.tui.input.KeyEvent;
import dev.tuink.tui.ui.MenuBar;
import java.io.PrintWriter;

final class InputActionDispatcher {
    private InputActionDispatcher() {
    }

    static DispatchResult dispatch(KeyEvent.Action action, UiSessionState ui, DispatchContext ctx) {
        String statusMessage = null;
        switch (action) {
            case CTRL_E -> {
                ctx.topMenu().openEdit();
                return DispatchResult.handled(null);
            }
            case CTRL_T -> {
                ctx.topMenu().openTab();
                return DispatchResult.handled(null);
            }
            case OPEN_VIEW_MENU -> {
                ctx.topMenu().openView();
                return DispatchResult.handled(null);
            }
            case OPEN_HELP_MENU -> {
                ctx.topMenu().openHelp();
                return DispatchResult.handled(null);
            }
            case F10 -> {
                ctx.topMenu().openFile();
                return DispatchResult.handled(null);
            }
            case F2 -> {
                ctx.topMenu().openSettings();
                return DispatchResult.handled(null);
            }
            case TOGGLE_TAB_BAR -> {
                ui.showTabBarSetting = !ui.showTabBarSetting;
                statusMessage = ui.showTabBarSetting ? "Tab bar shown" : "Tab bar hidden";
                return DispatchResult.handled(statusMessage);
            }
            case TOGGLE_WRAP_LINES -> {
                ui.wrapLinesSetting = !ui.wrapLinesSetting;
                if (ui.wrapLinesSetting) {
                    ctx.state().setTopWrapSegment(0);
                    ctx.state().setLeftColumn(0);
                    ctx.state().setLeftVisualColumn(0);
                }
                statusMessage = ui.wrapLinesSetting ? "Wrap lines enabled" : "Wrap lines disabled";
                return DispatchResult.handled(statusMessage);
            }
            case TOGGLE_WORD_WRAP -> {
                ui.wrapWordsSetting = !ui.wrapWordsSetting;
                statusMessage = ui.wrapWordsSetting ? "Word wrap enabled" : "Word wrap disabled";
                return DispatchResult.handled(statusMessage);
            }
            case TOGGLE_READING_MODE -> {
                ui.readingModeSetting = !ui.readingModeSetting;
                statusMessage = ui.readingModeSetting ? "Reading mode enabled" : "Reading mode disabled";
                return DispatchResult.handled(statusMessage);
            }
            case TOGGLE_CONFIRM_ON_CLOSE -> {
                ui.confirmOnCloseSetting = !ui.confirmOnCloseSetting;
                statusMessage = ui.confirmOnCloseSetting
                    ? "Confirm on close enabled"
                    : "Confirm on close disabled";
                return DispatchResult.handled(statusMessage);
            }
            case TOGGLE_MOUSE_MODE -> {
                ui.mouseModeSetting = !ui.mouseModeSetting;
                PrintWriter writer = ctx.writer();
                writer.print(ui.mouseModeSetting ? ctx.enableMouseTracking() : ctx.disableMouseTracking());
                writer.flush();
                if (ui.mouseModeSetting) {
                    return DispatchResult.handled("Mouse mode enabled");
                }
                return DispatchResult.handled(
                    "Mouse mode disabled. Press Alt+S to open Settings and re-enable mouse, or Ctrl+/ for keymap help.",
                    StatusLevel.ERROR
                );
            }
            case TOGGLE_ACTIVE_GUTTER_HIGHLIGHT -> {
                ui.highlightActiveGutterSetting = !ui.highlightActiveGutterSetting;
                statusMessage = ui.highlightActiveGutterSetting
                    ? "Current line number highlight enabled"
                    : "Current line number highlight disabled";
                return DispatchResult.handled(statusMessage);
            }
            case TOGGLE_SMART_HOME_END -> {
                ui.smartHomeEndSetting = !ui.smartHomeEndSetting;
                statusMessage = ui.smartHomeEndSetting
                    ? "Smart Home/End enabled"
                    : "Smart Home/End disabled";
                return DispatchResult.handled(statusMessage);
            }
            case TOGGLE_AUTO_INDENT -> {
                ui.autoIndentSetting = !ui.autoIndentSetting;
                statusMessage = ui.autoIndentSetting
                    ? "Auto indent enabled"
                    : "Auto indent disabled";
                return DispatchResult.handled(statusMessage);
            }
            case TOGGLE_AUTO_PAIRS -> {
                ui.autoPairsSetting = !ui.autoPairsSetting;
                statusMessage = ui.autoPairsSetting
                    ? "Autopairs enabled"
                    : "Autopairs disabled";
                return DispatchResult.handled(statusMessage);
            }
            case TOGGLE_AUTO_LIST -> {
                ui.autoListSetting = !ui.autoListSetting;
                statusMessage = ui.autoListSetting
                    ? "Auto list enabled"
                    : "Auto list disabled";
                return DispatchResult.handled(statusMessage);
            }
            case TOGGLE_VISIBLE_WHITESPACE -> {
                ui.visibleWhitespaceSetting = !ui.visibleWhitespaceSetting;
                statusMessage = ui.visibleWhitespaceSetting
                    ? "Visible whitespace enabled"
                    : "Visible whitespace disabled";
                return DispatchResult.handled(statusMessage);
            }
            case TOGGLE_SYNTAX_HIGHLIGHTING -> {
                ui.syntaxHighlightingSetting = !ui.syntaxHighlightingSetting;
                statusMessage = ui.syntaxHighlightingSetting
                    ? "Syntax highlighting enabled"
                    : "Syntax highlighting disabled";
                return DispatchResult.handled(statusMessage);
            }
            case TOGGLE_RAINBOW_BRACKETS -> {
                ui.rainbowBracketsSetting = !ui.rainbowBracketsSetting;
                statusMessage = ui.rainbowBracketsSetting
                    ? "Rainbow brackets enabled"
                    : "Rainbow brackets disabled";
                return DispatchResult.handled(statusMessage);
            }
            case TOGGLE_AUTO_SELECTION_HIGHLIGHT -> {
                ui.autoSelectionHighlightSetting = !ui.autoSelectionHighlightSetting;
                statusMessage = ui.autoSelectionHighlightSetting
                    ? "Auto highlight selection enabled"
                    : "Auto highlight selection disabled";
                return DispatchResult.handled(statusMessage);
            }
            case TOGGLE_GUTTER_DIFF -> {
                ui.gutterDiffModeSetting = nextGutterDiffMode(ui.gutterDiffModeSetting);
                statusMessage = "Gutter diff: " + ui.gutterDiffModeSetting.label();
                return DispatchResult.handled(statusMessage);
            }
            case TOGGLE_TAB_WIDTH -> {
                ui.tabWidthSetting = ui.tabWidthSetting <= 2 ? 4 : 2;
                statusMessage = ui.tabWidthSetting <= 2
                    ? "Thin tabs enabled (2)"
                    : "Thin tabs disabled (4)";
                return DispatchResult.handled(statusMessage);
            }
            case TOGGLE_TAB_INSERT_MODE -> {
                ui.tabInsertModeSetting = nextTabInsertMode(ui.tabInsertModeSetting);
                statusMessage = "Tab insert mode: " + ui.tabInsertModeSetting.label();
                return DispatchResult.handled(statusMessage);
            }
            case SHOW_KEY_HINTS -> {
                ui.helpHintsMode = true;
                return DispatchResult.handled(null);
            }
            case SHOW_LICENSES -> {
                ui.helpLicensesMode = true;
                return DispatchResult.handled(null);
            }
            case SHOW_AUTHOR -> {
                ui.helpAuthorMode = true;
                return DispatchResult.handled(null);
            }
            case CTRL_F -> {
                ui.searchMode = true;
                ui.searchFocus = SearchFocus.FIND;
                return DispatchResult.handled(null);
            }
            case CTRL_C -> {
                if (ctx.engine().hasSelection()) {
                    statusMessage = ctx.engine().copySelection() ? "Copied selection" : "Nothing to copy";
                } else {
                    statusMessage = ctx.engine().copyCurrentLine() ? "Copied line" : "Nothing to copy";
                }
                return DispatchResult.handled(statusMessage);
            }
            case CTRL_X -> {
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
            case CTRL_V -> {
                if (ui.readingModeSetting) {
                    statusMessage = "Reading mode: editing is disabled";
                } else {
                    statusMessage = ctx.engine().pasteClipboard() ? "Pasted" : "Clipboard empty";
                }
                return DispatchResult.handled(statusMessage);
            }
            case CTRL_Z -> {
                statusMessage = ctx.engine().undo() ? "Undo" : "Nothing to undo";
                return DispatchResult.handled(statusMessage);
            }
            case CTRL_Y -> {
                statusMessage = ctx.engine().redo() ? "Redo" : "Nothing to redo";
                return DispatchResult.handled(statusMessage);
            }
            case CTRL_A -> {
                statusMessage = ctx.engine().selectAll() ? "Selected all" : "Nothing to select";
                return DispatchResult.handled(statusMessage);
            }
            case TAB -> {
                if (ui.readingModeSetting) {
                    statusMessage = "Reading mode: editing is disabled";
                } else {
                    ctx.engine().insertTab();
                    statusMessage = "";
                }
                return DispatchResult.handled(statusMessage);
            }
            case ARROW_UP -> {
                ctx.engine().moveUp();
                return DispatchResult.handled(null);
            }
            case ARROW_DOWN -> {
                ctx.engine().moveDown();
                return DispatchResult.handled(null);
            }
            case ARROW_LEFT -> {
                ctx.engine().moveLeft();
                return DispatchResult.handled(null);
            }
            case ARROW_RIGHT -> {
                ctx.engine().moveRight();
                return DispatchResult.handled(null);
            }
            case SHIFT_ARROW_UP -> {
                ctx.engine().moveUp(true);
                return DispatchResult.handled(null);
            }
            case SHIFT_ARROW_DOWN -> {
                ctx.engine().moveDown(true);
                return DispatchResult.handled(null);
            }
            case SHIFT_ARROW_LEFT -> {
                ctx.engine().moveLeft(true);
                return DispatchResult.handled(null);
            }
            case SHIFT_ARROW_RIGHT -> {
                ctx.engine().moveRight(true);
                return DispatchResult.handled(null);
            }
            case WORD_LEFT -> {
                ctx.engine().moveWordLeft();
                return DispatchResult.handled(null);
            }
            case WORD_RIGHT -> {
                ctx.engine().moveWordRight();
                return DispatchResult.handled(null);
            }
            case PAGE_UP -> {
                ctx.engine().pageUp(ctx.contentRows());
                return DispatchResult.handled(null);
            }
            case PAGE_DOWN -> {
                ctx.engine().pageDown(ctx.contentRows());
                return DispatchResult.handled(null);
            }
            case HOME -> {
                if (ui.smartHomeEndSetting) {
                    ctx.engine().moveHomeSmart();
                } else {
                    ctx.engine().moveHome();
                }
                return DispatchResult.handled(null);
            }
            case END -> {
                if (ui.smartHomeEndSetting) {
                    ctx.engine().moveEndSmart();
                } else {
                    ctx.engine().moveEnd();
                }
                return DispatchResult.handled(null);
            }
            case BACKSPACE -> {
                if (ui.readingModeSetting) {
                    statusMessage = "Reading mode: editing is disabled";
                } else {
                    ctx.engine().backspace();
                }
                return DispatchResult.handled(statusMessage);
            }
            case DELETE -> {
                if (ui.readingModeSetting) {
                    statusMessage = "Reading mode: editing is disabled";
                } else {
                    ctx.engine().deleteForward();
                }
                return DispatchResult.handled(statusMessage);
            }
            case ENTER -> {
                if (ui.readingModeSetting) {
                    statusMessage = "Reading mode: editing is disabled";
                } else {
                    ctx.engine().insertNewline();
                }
                return DispatchResult.handled(statusMessage);
            }
            case ESCAPE, CTRL_R, UNKNOWN -> {
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

    record DispatchContext(
        EditorEngine engine,
        EditorState state,
        MenuBar topMenu,
        PrintWriter writer,
        String enableMouseTracking,
        String disableMouseTracking,
        int contentRows
    ) {
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
