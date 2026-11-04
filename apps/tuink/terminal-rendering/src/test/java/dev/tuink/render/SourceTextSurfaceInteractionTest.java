package dev.tuink.render;

import static org.junit.jupiter.api.Assertions.*;
import dev.tuink.core.*;
import dev.tuink.render.surface.TextSurfaceInteraction;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class SourceTextSurfaceInteractionTest {
    @Test void controllerComputesTargetsWithoutOwningOrMutatingTheEditor() {
        TextModel model = new TextModel(new SimpleLineBuffer());
        model.replaceLines(List.of("0123456789", "x", "abcdefghij"));
        EditorState state = new EditorState();
        EditorEngine engine = new EditorEngine(model, state, new TerminalEditorTextLayout(model));
        engine.moveCursorTo(new Position(0, 8));
        AtomicInteger commits = new AtomicInteger();
        var navigation = new EditorViewNavigation(engine, (target, selecting) -> {
            commits.incrementAndGet(); engine.navigateTo(target, selecting);
        });
        var geometry = new EditorViewCoordinates(engine.textLayout(), 20, false, false, 4);
        var planned = TextSurfaceInteraction.move(geometry, geometry.visualPosition(state.getCursor()),
            TextSurfaceInteraction.Move.DOWN, 1, -1);
        assertEquals(new Position(0, 8), state.getCursor(), "planning must not mutate the Source view");
        navigation.moveDown(true, 20, false, false);
        assertEquals(geometry.positionAt(planned.visual()), state.getCursor());
        assertEquals(new Position(0, 8), state.getSelectionAnchor());
        assertEquals(1, commits.get());
        navigation.moveDown(true, 20, false, false);
        assertEquals(new Position(2, 8), state.getCursor());
        assertEquals(2, commits.get());
        assertEquals(List.of("0123456789", "x", "abcdefghij"), model.snapshotLines());
        assertFalse(engine.undo());
    }

    @Test void sourceHitAdapterPreservesWrappedRowEndOwnership() {
        TextModel model = new TextModel(new SimpleLineBuffer());
        model.replaceLines(List.of("ab界cd"));
        var geometry = new EditorViewCoordinates(new TerminalEditorTextLayout(model), 4, true, false, 4);
        var visual = new TextSurfaceInteraction.Visual(0, 99);
        Position target = geometry.positionAt(visual);
        assertEquals(0, geometry.visualPosition(target).row());
        assertTrue(geometry.normalize(visual).column() <= geometry.rowWidth(0));
    }
}
