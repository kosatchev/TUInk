package dev.tuink.editor.diff;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;

class LineDiffContractTest {
    @Test
    void changedRangesDescribeInsertDeleteReplaceAndEmptyWithoutLibraryTypes() {
        assertEquals(LineEdit.Type.INSERT, new LineEdit(2, 2, 2, 4).type());
        assertEquals(LineEdit.Type.DELETE, new LineEdit(2, 4, 2, 2).type());
        assertEquals(LineEdit.Type.REPLACE, new LineEdit(2, 4, 2, 3).type());
        assertEquals(LineEdit.Type.EMPTY, new LineEdit(2, 2, 3, 3).type());
        assertThrows(IllegalArgumentException.class, () -> new LineEdit(-1, 0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new LineEdit(3, 2, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new LineEdit(0, 0, 3, 2));
    }

    @Test
    void optingOutRequiresNoProviderAndDoesNotReadTheText() {
        CharSequence unreadable = new CharSequence() {
            @Override public int length() { throw new AssertionError("Disabled diff read text"); }
            @Override public char charAt(int index) { throw new AssertionError("Disabled diff read text"); }
            @Override public CharSequence subSequence(int start, int end) { throw new AssertionError(); }
        };
        LineDiff none = LineDiff.none();
        assertSame(none, LineDiff.none());
        assertFalse(none.available());
        assertEquals(List.of(), none.compute(List.of(unreadable), List.of(unreadable), LineDiff.Algorithm.MYERS, 1L));
        assertThrows(IllegalArgumentException.class,
            () -> none.compute(List.of(), List.of(), LineDiff.Algorithm.MYERS, 0L));
    }
}
