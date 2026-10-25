package dev.tuink.tui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tuiframe.input.KeyStroke;
import dev.tuiframe.input.TextInput;
import dev.tuink.core.LineText;
import dev.tuink.core.Position;
import dev.tuink.core.RecoverySnapshot;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LongLineEditingIntegrationTest {
    @Test
    void typingAndRenderingShareBlocksAcrossLargeAsciiCyrillicAndEmojiLines(@TempDir Path directory)
        throws Exception {
        for (String unit : List.of("x", "я", "😀")) {
            int repetitions = 5 * 1024 * 1024 / unit.length();
            String original = unit.repeat(repetitions);
            ArrayDeque<Runnable> recovery = new ArrayDeque<>();
            try (EditorWindowFixture window = new EditorWindowFixture(directory, false,
                List.of(original), Runnable::run, recovery::add)) {
                var session = window.active().tabSession();
                window.resize(12, 80);
                for (boolean wrap : new boolean[] {false, true}) {
                    window.ui.wrapLinesSetting = wrap;
                    window.ui.wrapWordsSetting = true;
                    window.ui.syntaxHighlightingSetting = wrap;
                    window.ui.rainbowBracketsSetting = wrap;
                    for (int offset : new int[] {0, original.length() / 2, original.length()}) {
                        offset -= offset % unit.length();
                        session.engine().moveCursorTo(new Position(0, offset));
                        window.frame();
                        LineText.Work before = LineText.work();

                        window.event(new TextInput(unit));

                        assertBounded(before, unit + " insert, wrap=" + wrap + ", offset=" + offset);
                        assertTrue(session.model().lineText(0).edit().copiedCodeUnits() < 24 * 1024);
                        assertEquals(new Position(0, offset + unit.length()), session.state().getCursor());
                        assertEquals(original.length() + unit.length(), session.model().lineLength(0));
                        before = LineText.work();
                        window.event(KeyStroke.of(KeyStroke.Key.BACKSPACE));
                        assertBounded(before, unit + " backspace, wrap=" + wrap + ", offset=" + offset);
                        assertTrue(session.model().lineText(0).edit().copiedCodeUnits() < 24 * 1024);
                        assertEquals(original.length(), session.model().lineLength(0));
                        assertEquals(new Position(0, offset), session.state().getCursor());
                    }
                }
                assertEquals(1, recovery.size());
                recovery.remove().run();
                window.frame();
            }
        }
    }

    @Test
    void recoveryCaptureRetainsItsOwnTextVersionWithoutOwnerThreadMaterialization(@TempDir Path directory)
        throws Exception {
        ArrayDeque<Runnable> recovery = new ArrayDeque<>();
        try (EditorWindowFixture window = new EditorWindowFixture(directory, false,
            List.of("a".repeat(5 * 1024 * 1024)), Runnable::run, recovery::add)) {
            var session = window.active().tabSession();
            session.engine().insertInput("x");
            LineText.Work before = LineText.work();
            assertNull(window.recoveryManager.autosaveDirtyTabs(List.of(session), 0L));
            assertEquals(before.materializedCodeUnits(), LineText.work().materializedCodeUnits());
            session.engine().insertInput("y");

            recovery.remove().run();
            assertNull(window.recoveryManager.autosaveDirtyTabs(List.of(session), 1L));

            RecoverySnapshot saved = window.recoverySnapshots.values().iterator().next();
            assertTrue(saved.lines().getFirst().startsWith("xa"));
            assertEquals(new Position(0, 1), saved.cursor());
            assertEquals('y', session.model().lineText(0).charAt(1));
            while (!recovery.isEmpty()) recovery.remove().run();
        }
    }

    private static void assertBounded(LineText.Work before, String operation) {
        LineText.Work after = LineText.work();
        assertTrue(after.copiedCodeUnits() - before.copiedCodeUnits() < 65_536,
            operation + ": copied " + (after.copiedCodeUnits() - before.copiedCodeUnits()));
        assertTrue(after.materializedCodeUnits() - before.materializedCodeUnits() < 2 * 1024 * 1024,
            operation + ": materialized " + (after.materializedCodeUnits() - before.materializedCodeUnits()));
    }
}
