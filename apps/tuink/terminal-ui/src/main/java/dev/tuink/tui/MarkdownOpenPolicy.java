package dev.tuink.tui;

import java.nio.file.Path;
import java.util.Locale;

/** Presentation choice for a newly read Markdown file, independent of the current tab. */
record MarkdownOpenPolicy(boolean createPreview, boolean keepSource, boolean focusPreview) {
    static final MarkdownOpenPolicy SOURCE = new MarkdownOpenPolicy(false, true, false);

    static MarkdownOpenPolicy forNewFile(Path file, boolean dirty, boolean bothTabs, boolean inPreview) {
        if (dirty || !isMarkdown(file) || !bothTabs && !inPreview) return SOURCE;
        return new MarkdownOpenPolicy(true, bothTabs, inPreview);
    }

    static boolean isMarkdown(Path file) {
        if (file == null || file.getFileName() == null) return false;
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".md") || name.endsWith(".markdown") || name.endsWith(".mdown")
            || name.endsWith(".mkd") || name.endsWith(".mkdn");
    }
}
