package dev.tuink.core;

/**
 * Core tab session containing editor model, mutable state and engine.
 */
public record TabSession(TextModel model, EditorState state, EditorEngine engine) {
    public static TabSession createEmpty() {
        TextModel model = new TextModel(new SimpleLineBuffer());
        EditorState state = new EditorState();
        EditorEngine engine = new EditorEngine(model, state);
        return new TabSession(model, state, engine);
    }
}
