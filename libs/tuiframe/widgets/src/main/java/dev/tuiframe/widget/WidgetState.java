package dev.tuiframe.widget;

/**
 * Paint-only widget state. An interactive focus snapshot projects to this
 * value, keeping focus ownership and event routing outside passive widgets.
 */
public record WidgetState(boolean focused, boolean enabled) {
    public static final WidgetState NORMAL = new WidgetState(false, true);
    public static final WidgetState FOCUSED = new WidgetState(true, true);
    public static final WidgetState DISABLED = new WidgetState(false, false);

    public WidgetState {
        if (focused && !enabled) {
            throw new IllegalArgumentException("a disabled widget cannot be focused");
        }
    }
}
