package dev.tuiframe.widget;

/** Focus selected on scope entry and when the current target becomes unavailable. */
public enum FocusResetPolicy {
    /** Choose the first eligible target unless focus was explicitly cleared. */
    FIRST_FOCUSABLE,
    /** Leave focus cleared; losing a target must not automatically select another action. */
    CLEARED
}
