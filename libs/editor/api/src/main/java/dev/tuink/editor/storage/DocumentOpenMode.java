package dev.tuink.editor.storage;

/** An explicit choice applies to this open request only, never to later files. */
public enum DocumentOpenMode {
    DETECT,
    UTF_8,
    UTF_16LE,
    UTF_16BE,
    WINDOWS_1251,
    CP866,
    KOI8_R
}
