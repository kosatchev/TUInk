package dev.tuink.core;

/**
 * Per-line diff marker relative to the last saved/opened baseline.
 */
public enum GutterDiffFlag {
    NONE,
    ADDED,
    MODIFIED,
    DELETED
}
