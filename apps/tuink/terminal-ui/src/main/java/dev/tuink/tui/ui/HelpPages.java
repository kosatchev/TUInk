package dev.tuink.tui.ui;

public final class HelpPages {
    public static final HelpPage KEYMAP = new HelpPage(
        " Keyboard Hints ",
        new String[] {
            "Key                  Action",
            "-------------------- ----------------------------------",
            "",
            "              GLOBAL",
            "Ctrl+O               Open file dialog",
            "Ctrl+N               New tab",
            "Ctrl+S               Save",
            "Ctrl+K               Save As",
            "Ctrl+Q               Quit",
            "Ctrl+F               Open find bar",
            "Ctrl+/               Keyboard hints",
            "Ctrl+A               Select all",
            "Arrows/Home/End      Move cursor",
            "Ctrl+Left/Right      Move by word",
            "Alt+B / Alt+F        Move by word (fallback)",
            "PgUp/PgDn            Scroll page",
            "",
            "               MENU",
            "F10                  Open File menu",
            "Ctrl+E or Alt+E      Open Edit menu",
            "Alt+V                Open View menu",
            "Ctrl+T or Alt+T      Open Tab menu",
            "F2 or Alt+S          Open Settings menu",
            "F1 or Alt+H          Open Help menu",
            "Arrows               Move selection",
            "Enter                Activate item",
            "Letters / 1-9        Mnemonic selection",
            "Esc                  Close menu",
            "",
            "              SEARCH",
            "Tab                  Next search field/toggle",
            "Space                Toggle option",
            "Enter or Ctrl+F      Next match",
            "Ctrl+G               Previous match",
            "Ctrl+R               Replace current/next",
            "Ctrl+A               Replace all",
            "Esc                  Close search bar"
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
            "",
            "GitHub: https://github.com/kosatchev/tuink",
            "License docs: /LICENSE, /THIRD_PARTY_NOTICES.md",
        }
    );

    public static final HelpPage AUTHOR = new HelpPage(
        " Author ",
        new String[] {
            "Project: TUInk",
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
}
