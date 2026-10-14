package dev.tuiframe.widget;

/** Lifecycle boundary which ends the current gesture without synthesizing input. */
public enum PointerCaptureCancellation {
    RELEASE,
    KEYBOARD_INPUT,
    NEW_PRESS,
    RESIZE,
    FOCUS_CHANGED,
    SURFACE_CHANGED,
    GEOMETRY_CHANGED,
    DISABLED,
    HIDDEN
}
