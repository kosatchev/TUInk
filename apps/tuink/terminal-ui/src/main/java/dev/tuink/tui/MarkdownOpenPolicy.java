package dev.tuink.tui;

import java.nio.file.Path;
import dev.tuink.editor.language.LanguageId;
import dev.tuink.editor.language.LanguageRegistry;

/** Presentation choice for a newly read Markdown file, independent of the current tab. */
record MarkdownOpenPolicy(boolean createPreview, boolean keepSource, boolean focusPreview) {
    static final MarkdownOpenPolicy SOURCE = new MarkdownOpenPolicy(false, true, false);

    static MarkdownOpenPolicy forNewFile(Path file, boolean dirty, boolean bothTabs, boolean inPreview) {
        if (dirty || !isMarkdown(file) || !bothTabs && !inPreview) return SOURCE;
        return new MarkdownOpenPolicy(true, bothTabs, inPreview);
    }

    static boolean isMarkdown(Path file) {
        if (file == null || file.getFileName() == null) return false;
        return LanguageRegistry.forFileName(file.getFileName().toString()) == LanguageId.MARKDOWN;
    }
}
