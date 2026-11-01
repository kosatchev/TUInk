package dev.tuink.editor.format;

/** KEEP retains every existing separator and the default used by new lines. */
public enum LineEndingPolicy {
    KEEP(null), LF("\n"), CRLF("\r\n"), CR("\r");

    private final String separator;
    LineEndingPolicy(String separator) { this.separator = separator; }
    public String separator() { return separator; }
}
