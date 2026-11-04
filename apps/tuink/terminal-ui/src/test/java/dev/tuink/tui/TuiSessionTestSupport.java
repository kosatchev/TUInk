package dev.tuink.tui;

import dev.tuink.core.TabSession;
import dev.tuink.core.EditorEngine;
import dev.tuink.core.EditorState;
import dev.tuink.render.EditorViewNavigation;
import java.util.function.Consumer;
import dev.tuink.editor.command.DocumentCommand;
import dev.tuink.editor.command.DocumentCommandResult;
import java.util.function.Function;

/** Test composition through the same registered document session used by terminal windows. */
public final class TuiSessionTestSupport {
    private TuiSessionTestSupport() {}

    static InputActionDispatcher.DispatchContext context(EditorEngine engine, EditorState state,
        int rows, int columns, EditorViewNavigation navigation) {
        TuiDocumentSession session = engine == null ? null : TuiDocumentSession.adopt(
            new TabSession(engine.model(), state, engine.textLayout(), engine));
        return new InputActionDispatcher.DispatchContext(engine, rows, columns, navigation, session);
    }

    public static EditorViewNavigation navigation(EditorEngine engine) {
        TuiDocumentSession session = TuiDocumentSession.adopt(
            new TabSession(engine.model(), engine.state(), engine.textLayout(), engine));
        return new EditorViewNavigation(engine, session::navigate);
    }

    public static Function<DocumentCommand, DocumentCommandResult.Applied> commands(TabSession view) {
        return TuiDocumentSession.adopt(view)::execute;
    }
}
