package dev.tuink.tui.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class HelpPagesTest {
    @Test
    void versionComesFromGeneratedResource() {
        assertEquals("0.12.0", HelpPages.appVersion());
    }

    @Test
    void pageRowsAreDefensivelyCopiedAndImmutable() {
        String[] rows = {"first"};
        HelpPage page = new HelpPage("title", rows);

        rows[0] = "changed";

        assertEquals("first", page.rows().get(0));
        assertThrows(UnsupportedOperationException.class, () -> page.rows().add("second"));
    }
}
