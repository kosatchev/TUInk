package dev.tuink.core;

/**
 * Core tab session containing editor model, mutable state, layout port and engine.
 */
public record TabSession(
    TextModel model,
    EditorState state,
    EditorTextLayout textLayout,
    EditorEngine engine
) {
    public TabSession {
        if (model == null
            || state == null
            || textLayout == null
            || engine == null
            || textLayout.model() != model
            || engine.model() != model
            || engine.state() != state
            || engine.textLayout() != textLayout) {
            throw new IllegalArgumentException("tab components must share model, state and textLayout");
        }
    }

    public static TabSession createEmpty(
        EditorTextLayout.Factory textLayoutFactory,
        EditorClipboard clipboard
    ) {
        TextModel model = new TextModel(new SimpleLineBuffer());
        EditorState state = new EditorState();
        EditorTextLayout textLayout = textLayoutFactory.create(model);
        EditorEngine engine = new EditorEngine(model, state, textLayout, clipboard);
        return new TabSession(model, state, textLayout, engine);
    }
}
