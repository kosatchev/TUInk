package dev.tuink.tui.ui;

import java.io.InputStream;
import java.util.Properties;

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
            "Ctrl+H               Show hidden files",
            "Arrows/Home/End      Move cursor",
            "Ctrl+Left/Right      Move by word",
            "Alt+B / Alt+F        Move by word (fallback)",
            "PgUp/PgDn            Scroll page",
            "",
            "               MENU",
            "F1 or Alt+I          Open File menu",
            "F2 or Alt+E          Open Edit menu",
            "F3 or Alt+V          Open View menu",
            "F4 or Alt+T          Open Tab menu",
            "F5 or Alt+S          Open Settings menu",
            "F6 or Alt+H          Open Help menu",
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
            "- JGit 6.10.0.202406032230-r (EDL-1.0, includes MIT component)",
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
