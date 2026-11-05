package dev.tuink.tui.flow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import dev.tuink.tui.flow.ConfirmationShortcuts.Key;
import java.util.List;
import org.junit.jupiter.api.Test;

class ConfirmationShortcutsTest {
    @Test
    void recoveryOffersExplicitDeleteAndQuitWhileNoAndEscapeKeepDraftsForLater() {
        var recovery = ConfirmationShortcuts.RECOVERY;
        assertEquals(ConfirmChoice.SAVE, recovery.decision(Key.YES));
        assertNull(recovery.decision(Key.SAVE));
        assertEquals(ConfirmChoice.CANCEL, recovery.decision(Key.NO));
        assertEquals(ConfirmChoice.DISCARD, recovery.decision(Key.DELETE));
        assertEquals(ConfirmChoice.CANCEL, recovery.decision(Key.ESCAPE));
        assertEquals(ConfirmationAction.Command.QUIT, recovery.decision(Key.QUIT));
        assertEquals("Y", recovery.keysFor(ConfirmChoice.SAVE));
        assertEquals("D", recovery.keysFor(ConfirmChoice.DISCARD));
        assertEquals("N/Esc", recovery.keysFor(ConfirmChoice.CANCEL));
        assertEquals("Ctrl+Q", recovery.keysFor(ConfirmationAction.Command.QUIT));
        assertEquals("Quit", ConfirmationAction.Command.QUIT.label());
        assertEquals(List.of(ConfirmChoice.SAVE, ConfirmChoice.DISCARD, ConfirmChoice.CANCEL),
            List.of(ConfirmChoice.values()), "Quit is a shortcut command, not a fourth button choice");
    }

    @Test
    void writingAndOpeningPoliciesKeepTheirExistingDecisionsAndDisplayedKeyOrder() {
        for (var policy : List.of(ConfirmationShortcuts.STANDARD, ConfirmationShortcuts.SAVE,
            ConfirmationShortcuts.SAVE_CHANGES, ConfirmationShortcuts.QUIT)) {
            assertEquals(ConfirmChoice.SAVE, policy.decision(Key.YES));
            assertNull(policy.decision(Key.DELETE), policy.name());
            assertEquals(ConfirmChoice.CANCEL, policy.decision(Key.ESCAPE));
            assertEquals("", policy.keysFor(ConfirmationAction.Command.QUIT), policy.name());
        }
        assertNull(ConfirmationShortcuts.STANDARD.decision(Key.SAVE));
        assertEquals(ConfirmChoice.CANCEL, ConfirmationShortcuts.STANDARD.decision(Key.NO));
        assertEquals(ConfirmChoice.CANCEL, ConfirmationShortcuts.STANDARD.decision(Key.QUIT));
        assertKeys(ConfirmationShortcuts.STANDARD, "Y", "", "N/Esc/Ctrl+Q");

        assertEquals(ConfirmChoice.SAVE, ConfirmationShortcuts.SAVE.decision(Key.SAVE));
        assertEquals(ConfirmChoice.CANCEL, ConfirmationShortcuts.SAVE.decision(Key.NO));
        assertEquals(ConfirmChoice.CANCEL, ConfirmationShortcuts.SAVE.decision(Key.QUIT));
        assertKeys(ConfirmationShortcuts.SAVE, "Y/Ctrl+S", "", "N/Esc/Ctrl+Q");

        assertEquals(ConfirmChoice.SAVE, ConfirmationShortcuts.SAVE_CHANGES.decision(Key.SAVE));
        assertEquals(ConfirmChoice.DISCARD, ConfirmationShortcuts.SAVE_CHANGES.decision(Key.NO));
        assertEquals(ConfirmChoice.CANCEL, ConfirmationShortcuts.SAVE_CHANGES.decision(Key.QUIT));
        assertKeys(ConfirmationShortcuts.SAVE_CHANGES, "Y/Ctrl+S", "N", "Esc/Ctrl+Q");

        assertEquals(ConfirmChoice.SAVE, ConfirmationShortcuts.QUIT.decision(Key.SAVE));
        assertEquals(ConfirmChoice.DISCARD, ConfirmationShortcuts.QUIT.decision(Key.NO));
        assertEquals(ConfirmChoice.DISCARD, ConfirmationShortcuts.QUIT.decision(Key.QUIT));
        assertKeys(ConfirmationShortcuts.QUIT, "Y/Ctrl+S", "N/Ctrl+Q", "Esc");
    }

    @Test
    void textDecisionsAcceptOnlyExactAsciiMnemonicLettersAndFollowEachPolicy() {
        for (var policy : ConfirmationShortcuts.values()) {
            for (String text : List.of("y", "Y"))
                assertEquals(ConfirmChoice.SAVE, policy.textDecision(text), policy + " " + text);
            for (String text : List.of("n", "N"))
                assertEquals(policy.decision(Key.NO), policy.textDecision(text), policy + " " + text);
            for (String text : List.of("d", "D")) {
                if (policy == ConfirmationShortcuts.RECOVERY)
                    assertEquals(ConfirmChoice.DISCARD, policy.textDecision(text));
                else assertNull(policy.textDecision(text), policy + " " + text);
            }
            assertNull(policy.textDecision(null), policy.name());
            for (String text : List.of("", " ", "yes", "no", "delete", "dd", "D ", "q", "Q", "Ctrl+Q", "\n", "\u001b", "😀", "Y\u0301", "Ｄ"))
                assertNull(policy.textDecision(text), policy + " " + text);
        }
    }

    private static void assertKeys(ConfirmationShortcuts policy, String save, String discard, String cancel) {
        assertEquals(save, policy.keysFor(ConfirmChoice.SAVE), policy.name());
        assertEquals(discard, policy.keysFor(ConfirmChoice.DISCARD), policy.name());
        assertEquals(cancel, policy.keysFor(ConfirmChoice.CANCEL), policy.name());
    }
}
