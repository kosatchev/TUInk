package dev.tuink.tui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tuink.tui.style.UiStyles;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class TuiCliOptionsTest {
    @Test
    void defaultsLeaveOptionalOverridesUnset() {
        TuiCliOptions options = TuiCliOptions.parse(new String[0]);

        assertNull(options.file());
        assertNull(options.mouseEnabledOverride());
        assertTrue(options.altScreenEnabled());
        assertTrue(options.colorEnabled());
        assertNull(options.readModeOverride());
        assertFalse(options.showHelp());
        assertNull(options.themeOverride());
        assertFalse(options.profileEnabled());
        assertNull(options.profileFile());
    }

    @Test
    void parsesFrontendFlagsAndFile() {
        TuiCliOptions options = TuiCliOptions.parse(new String[] {
            "--no-mouse",
            "--no-alt-screen",
            "--no-color",
            "--read",
            "--theme=light",
            "notes.txt"
        });

        assertEquals(Path.of("notes.txt"), options.file());
        assertEquals(Boolean.FALSE, options.mouseEnabledOverride());
        assertFalse(options.altScreenEnabled());
        assertFalse(options.colorEnabled());
        assertEquals(Boolean.TRUE, options.readModeOverride());
        assertEquals(UiStyles.Theme.LIGHT, options.themeOverride());
    }

    @Test
    void profileFileEnablesProfilingInBothForms() {
        TuiCliOptions separate = TuiCliOptions.parse(new String[] {"--profile-file", "first.log"});
        TuiCliOptions inline = TuiCliOptions.parse(new String[] {"--profile-file=second.log"});

        assertTrue(separate.profileEnabled());
        assertEquals(Path.of("first.log"), separate.profileFile());
        assertTrue(inline.profileEnabled());
        assertEquals(Path.of("second.log"), inline.profileFile());
    }

    @Test
    void parsesHelpAliases() {
        assertTrue(TuiCliOptions.parse(new String[] {"--help"}).showHelp());
        assertTrue(TuiCliOptions.parse(new String[] {"-h"}).showHelp());
    }

    @Test
    void rejectsMissingProfileFileValue() {
        IllegalArgumentException missing = assertThrows(
            IllegalArgumentException.class,
            () -> TuiCliOptions.parse(new String[] {"--profile-file"})
        );

        assertEquals("Missing value after --profile-file", missing.getMessage());
    }

    @Test
    void preservesEmptyInlineProfilePathBehavior() {
        TuiCliOptions options = TuiCliOptions.parse(new String[] {"--profile-file="});

        assertTrue(options.profileEnabled());
        assertEquals(Path.of(""), options.profileFile());
    }

    @Test
    void rejectsUnknownOptionAndMultipleFiles() {
        assertThrows(
            IllegalArgumentException.class,
            () -> TuiCliOptions.parse(new String[] {"--unknown"})
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> TuiCliOptions.parse(new String[] {"one.txt", "two.txt"})
        );
    }
}
