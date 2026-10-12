package dev.tuink.tui.ui;

import java.io.InputStream;
import java.util.Properties;

public final class HelpPages {
    public static final HelpPage KEYMAP = new HelpPage(
        " Keyboard Hints ",
        new String[] {
            "Key                     Action",
            "----------------------- -------------------------------------",
            "",
            "EDITOR",
            "Ctrl+O                  Open file dialog",
            "Ctrl+N                  New tab",
            "Ctrl+S                  Save",
            "Ctrl+K / Ctrl+W         Save As",
            "Ctrl+Shift+S            Save As",
            "Ctrl+Shift+F4           Save As",
            "Ctrl+Q                  Request quit",
            "Ctrl+Z / Ctrl+Y         Undo / redo",
            "Ctrl+X / Ctrl+C         Cut / copy selection or current line",
            "Ctrl+V                  Paste application clipboard",
            "Ctrl+A                  Select all document text",
            "Ctrl+/                  Toggle line comment",
            "Ctrl+F                  Open search and replace",
            "Ctrl+Tab / Ctrl+PgDn    Next tab",
            "Super+Tab               Next tab",
            "Ctrl+PgUp               Previous tab",
            "Close a tab through Tab > Close Tab.",
            "",
            "Arrows / Home / End     Move cursor",
            "PgUp / PgDn             Move one displayed page",
            "Shift+Arrows            Extend selection",
            "Ctrl+Left / Ctrl+Right  Move by word",
            "Alt+B / Alt+F           Move by word (fallback)",
            "Tab / Enter             Insert tab / newline",
            "Backspace / Delete      Delete backward / forward",
            "Modified keys need terminal support; the OS may intercept Super.",
            "Save / Copy keep manual scroll; each tab remembers its view.",
            "",
            "MENUS",
            "F1 or Alt+I             Open File menu",
            "F2 or Alt+E             Open Edit menu",
            "F3 or Alt+V             Open View menu",
            "F4 or Alt+T             Open Tab menu",
            "F5 or Alt+S             Open Settings menu",
            "F6 or Alt+H             Open Help menu",
            "Arrows                  Move selection",
            "Enter                   Activate item",
            "Letters / 1-9           Mnemonic selection",
            "Esc                     Close menu",
            "",
            "SEARCH",
            "Ctrl+F                  Focus Find / next match in search panel",
            "Enter (field focused)   Next match",
            "Enter / Space (button)  Activate action / toggle option",
            "Ctrl+G                  Previous match",
            "Ctrl+R                  Replace current/next match",
            "All button              Replace all in active tab",
            "Ctrl+A                  Select all text in focused field",
            "Ctrl+C / Ctrl+X         Copy / cut selected field text",
            "Ctrl+V                  Paste, replacing field selection",
            "Ctrl+Left/Right         Move field caret by word",
            "Alt+B / Alt+F           Move field caret by word",
            "Shift+Left/Right/Home/End Select field text",
            "Ctrl+Shift+Left/Right   Select field text by word",
            "Drag / Shift+Click      Select field text with the mouse",
            "Ctrl+Backspace/Delete  Delete field text by word",
            "No field selection: Copy/Cut leave clipboard unchanged.",
            "Tab / Shift+Tab         Cycle search controls and document",
            "Ctrl+Z / Ctrl+Y         Undo / redo only with document focused",
            "Ctrl+C / Ctrl+X / Ctrl+V Ignored on buttons and search options",
            "Arrows (button focused) Move control focus",
            "Up / Down (field)       Stay in the single-line field",
            "Left/Right/Home/End     Move within focused field",
            "PgUp/PgDn / wheel       Scroll document, keeping field focus",
            "Esc                     Close search bar",
            "",
            "DIALOG FOCUS",
            "Search allows editing the document while the panel stays open.",
            "Save As / confirmations include the document in Tab traversal.",
            "They allow document selection, copy, navigation, and scrolling.",
            "Help / file picker keep focus inside and block all document input.",
            "Rejected input highlights the dialog and focuses its first control.",
            "",
            "FILE PICKER",
            "Ctrl+H                  Show / hide hidden files",
            "Backspace               Parent directory (list focused)",
            "Shift+Left/Right        Scroll tree horizontally",
            "Left / Right            Collapse / expand tree",
            "Up / Down               Select previous / next entry",
            "PgUp / PgDn             Move selection by a page",
            "Enter                   Open selection / activate button",
            "Space (button focused)  Activate OK / Cancel",
            "Tab / Shift+Tab         Cycle controls inside the dialog",
            "Esc                     Close file dialog",
            "Ctrl+H acts as Backspace when both send the same ^H byte.",
            "",
            "SAVE AS",
            "Tab / Shift+Tab         Cycle filename, Save, Cancel, document",
            "Ctrl+C / Ctrl+X / Ctrl+V Copy / cut / paste in filename field",
            "Field navigation and selection are the same as Find / Replace.",
            "Enter (field focused)   Save using the entered name",
            "Enter / Space (button)  Activate Save / Cancel",
            "Save errors return focus to the filename; empty names are rejected.",
            "Esc / Ctrl+Q            Cancel Save As",
            "",
            "CONFIRMATIONS",
            "Tab / Shift+Tab         Cycle buttons and document",
            "With a confirmation button focused:",
            "Left / Right            Previous / next button",
            "Home / End              First / last button",
            "Enter / Space           Activate focused button",
            "Y / N                   Prompt-specific Yes / No",
            "Y/N: overwrite = replace/cancel; close tab = discard/cancel.",
            "Y/N: quit = discard/cancel; recovery = restore/delete snapshots.",
            "Esc cancels, except in recovery where it deletes snapshots.",
            "",
            "Ctrl+Q in dialogs:",
            "  Save As / overwrite / close-tab confirmation: cancel.",
            "  Quit confirmation: discard unsaved edits and exit.",
            "  Recovery prompt: discard and delete recovery snapshots.",
            "",
            "HELP",
            "Help > Keyboard hints   Show this page",
            "Tab / Shift+Tab         Keep focus inside help",
            "Up/Down, PgUp/PgDn       Scroll help",
            "Home / End              First / last help page",
            "Mouse wheel             Scroll help under the pointer",
            "Esc / Enter / Space     Close help"
        }
    );

    public static final HelpPage LICENSES = new HelpPage(
        " Licenses ",
        new String[] {
            "Project license: Apache License 2.0",
            "File: LICENSE",
            "",
            "Bundled/runtime components:",
            "- Gradle Wrapper (Apache-2.0)",
            "- JLine 3.30.6 (BSD-3-Clause)",
            "- JGit 6.10.1.202505221210-r (EDL-1.0, includes MIT component)",
            "",
            "GitHub: https://github.com/kosatchev/tuink",
            "License docs: /LICENSE, /THIRD_PARTY_NOTICES.md",
        }
    );

    public static final HelpPage AUTHOR = new HelpPage(
        " About ",
        new String[] {
            "Application: TUInk",
            "Version: " + appVersion(),
            "Author: Dmitry Kosatchev",
            "Contact: kosatchev@yandex.com",
            "",
            "GitHub: https://github.com/kosatchev",
            "Repository: https://github.com/kosatchev/tuink",
            "",
            "Support / Donate:",
            "Support is optional, but always appreciated.",
            "If you'd like to help the project, visit my GitHub profile.",
        }
    );

    private HelpPages() {
    }

    static String appVersion() {
        try (InputStream in = HelpPages.class.getResourceAsStream("/tuink-version.properties")) {
            if (in == null) {
                return "dev";
            }
            Properties p = new Properties();
            p.load(in);
            String version = p.getProperty("version");
            if (version == null || version.isBlank()) {
                throw new IllegalStateException("Generated TUInk version resource has no version");
            }
            return version.trim();
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Failed to read generated TUInk version resource", e);
        }
    }
}
