package dev.tuink.tui.input;

/**
 * A physical input event produced by a TUI backend.
 *
 * <p>These events describe terminal input without assigning application
 * commands to keys. Keymaps and focus routing belong above this layer.</p>
 */
public sealed interface TuiInputEvent permits KeyStroke, TextInput, PasteEvent, PointerEvent, ResizeEvent {
}
