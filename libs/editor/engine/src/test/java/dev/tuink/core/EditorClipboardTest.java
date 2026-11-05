package dev.tuink.core;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class EditorClipboardTest {
    @Test void eachExplicitPublicationAdvancesEvenForEqualOrEmptyText() {
        var clipboard = new EditorClipboard();
        assertEquals(0, clipboard.revision());
        String value = new String("copied");
        clipboard.setText(value);
        assertSame(value, clipboard.text());
        assertEquals(1, clipboard.revision());
        clipboard.setText(value);
        assertEquals(2, clipboard.revision());
        clipboard.setText("");
        assertEquals(3, clipboard.revision());
    }

    @Test void rejectedPublicationPreservesTextAndToken() {
        var clipboard = new EditorClipboard();
        clipboard.setText("keep");
        long before = clipboard.revision();
        assertThrows(NullPointerException.class, () -> clipboard.setText(null));
        assertEquals("keep", clipboard.text());
        assertEquals(before, clipboard.revision());
    }

    @Test void unpublishedRollbackRestoresBothTextAndPublicationToken() {
        var clipboard = new EditorClipboard();
        clipboard.setText("committed");
        String committed = clipboard.text();
        long before = clipboard.revision();
        clipboard.setText("unpublished");
        clipboard.restore(committed, before);
        assertSame(committed, clipboard.text());
        assertEquals(before, clipboard.revision());
        clipboard.setText(committed);
        assertEquals(before + 1, clipboard.revision());
    }

    @Test void publicationTokenWrapsWithoutRefusingTheCopiedText() {
        var clipboard = new EditorClipboard();
        clipboard.restore("old", Long.MAX_VALUE);
        clipboard.setText("new");
        assertEquals("new", clipboard.text());
        assertEquals(Long.MIN_VALUE, clipboard.revision());
    }
}
