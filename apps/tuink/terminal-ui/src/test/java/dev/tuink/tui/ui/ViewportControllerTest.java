package dev.tuink.tui.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.tuink.core.EditorEngine;
import dev.tuink.core.EditorState;
import dev.tuink.core.SimpleLineBuffer;
import dev.tuink.core.TextModel;
import dev.tuink.render.TerminalEditorTextLayout;
import dev.tuink.tui.command.TuinkCommand;
import dev.tuink.tui.input.EditorInput;
import dev.tuiframe.input.KeyStroke;
import dev.tuiframe.input.PointerEvent;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ViewportControllerTest {
    @Test
    void rawPointerPreservesManualViewportPanning() {
        Fixture fixture = manuallyPannedFixture();

        fixture.controller().onInput(new EditorInput(pointer(), null));
        fixture.ensureCursorVisible();

        assertEquals(7, fixture.state().getTopLine());
        assertEquals(10, fixture.state().getLeftVisualColumn());
    }

    @Test
    void semanticCommandRestoresCursorFollowing() {
        Fixture fixture = manuallyPannedFixture();

        fixture.controller().onInput(EditorInput.command(TuinkCommand.CURSOR_RIGHT));
        fixture.ensureCursorVisible();

        assertEquals(0, fixture.state().getTopLine());
        assertEquals(0, fixture.state().getLeftVisualColumn());
    }

    @Test
    void pointerResolvedToACommandRestoresCursorFollowing() {
        Fixture fixture = manuallyPannedFixture();

        fixture.controller().onInput(new EditorInput(pointer(), TuinkCommand.EDIT_SELECT_ALL));
        fixture.ensureCursorVisible();

        assertEquals(0, fixture.state().getTopLine());
        assertEquals(0, fixture.state().getLeftVisualColumn());
    }

    @Test
    void unboundRawKeyRestoresCursorFollowing() {
        Fixture fixture = manuallyPannedFixture();

        fixture.controller().onInput(new EditorInput(
            KeyStroke.of(KeyStroke.Key.ARROW_DOWN, KeyStroke.Modifier.CTRL),
            null
        ));
        fixture.ensureCursorVisible();

        assertEquals(0, fixture.state().getTopLine());
        assertEquals(0, fixture.state().getLeftVisualColumn());
    }

    private static PointerEvent pointer() {
        return new PointerEvent(
            2,
            3,
            PointerEvent.Kind.DRAG,
            PointerEvent.Button.LEFT,
            Set.of(KeyStroke.Modifier.SHIFT)
        );
    }

    private static Fixture manuallyPannedFixture() {
        SimpleLineBuffer buffer = new SimpleLineBuffer();
        buffer.setLine(0, "0123456789".repeat(8));
        for (int line = 1; line < 20; line++) {
            buffer.insertLine(line, "row-" + line);
        }
        TextModel model = new TextModel(buffer);
        EditorState state = new EditorState();
        EditorEngine engine = new EditorEngine(model, state, new TerminalEditorTextLayout(model));
        state.setTopLine(7);
        state.setLeftColumn(10);
        state.setLeftVisualColumn(10);
        ViewportController controller = new ViewportController();
        controller.onScrollbarInteraction(
            new EditorView.ScrollPointerResult(true, EditorView.ScrollAxis.HORIZONTAL, 0),
            0,
            10,
            0,
            7
        );
        return new Fixture(state, engine, controller);
    }

    private record Fixture(EditorState state, EditorEngine engine, ViewportController controller) {
        private void ensureCursorVisible() {
            controller.ensureCursorVisible(engine, 3, 8, false, false);
        }
    }
}
