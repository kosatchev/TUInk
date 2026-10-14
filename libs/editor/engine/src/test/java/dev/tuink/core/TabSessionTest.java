package dev.tuink.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class TabSessionTest {
    @Test
    void createsOneLayoutSharedByTheSessionAndEngine() {
        AtomicInteger creations = new AtomicInteger();

        TabSession session = TabSession.createEmpty(model -> {
            creations.incrementAndGet();
            return new TestEditorTextLayout(model);
        }, new EditorClipboard());

        assertEquals(1, creations.get());
        assertSame(session.model(), session.textLayout().model());
        assertSame(session.textLayout(), session.engine().textLayout());
    }

    @Test
    void rejectsComponentsFromDifferentEditorSessions() {
        TextModel model = new TextModel(new SimpleLineBuffer());
        EditorState state = new EditorState();
        EditorTextLayout layout = new TestEditorTextLayout(model);
        EditorEngine engine = new EditorEngine(model, state, layout);
        TextModel foreignModel = new TextModel(new SimpleLineBuffer());
        EditorState foreignState = new EditorState();
        EditorTextLayout foreignLayout = new TestEditorTextLayout(foreignModel);
        TabSession secondView = engine.document().attach(TestEditorTextLayout::new, new EditorClipboard());

        assertThrows(
            IllegalArgumentException.class,
            () -> new TabSession(foreignModel, state, layout, engine)
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> new TabSession(model, foreignState, layout, engine)
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> new TabSession(model, state, foreignLayout, engine)
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> new TabSession(model, secondView.state(), layout, secondView.engine())
        );
    }
}
