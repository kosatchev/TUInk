package dev.tuiframe.widget;

/** How a dialog permits access to the content behind it. */
public enum DialogMode {
    /** Content may be focused and read, but changes are rejected. */
    BLOCKING,
    /** Content may be focused, read, and changed. */
    NON_BLOCKING,
    /** Focus and input stay inside the dialog; underlying content is inaccessible. */
    EXCLUSIVE;

    /** Whether the underlying content may participate in focus traversal and pointer routing. */
    public boolean permitsContentFocus() {
        return this != EXCLUSIVE;
    }
}
