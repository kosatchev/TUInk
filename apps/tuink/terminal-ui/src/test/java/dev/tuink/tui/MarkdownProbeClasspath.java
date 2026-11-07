package dev.tuink.tui;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

/** Optional runtime additions for child JVM probes that construct the real editor window. */
final class MarkdownProbeClasspath {
    private MarkdownProbeClasspath() {}

    static void addTo(Set<String> locations) throws Exception {
        for (String name : List.of(
            "dev.tuiframe.explorer.Explorer",
            "dev.tuink.markdown.preview.PreviewCoordinator",
            "dev.tuink.markdown.commonmark.CommonmarkMarkdownParser",
            "dev.tuink.markdown.math.builtin.BuiltinMathProvider",
            "dev.tuink.markdown.mermaid.builtin.BuiltinMermaidProvider",
            "org.commonmark.parser.Parser",
            "org.commonmark.ext.gfm.tables.TablesExtension",
            "org.commonmark.ext.gfm.strikethrough.StrikethroughExtension",
            "org.commonmark.ext.task.list.items.TaskListItemsExtension"
        )) {
            Class<?> type = Class.forName(name, false, MarkdownProbeClasspath.class.getClassLoader());
            locations.add(Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toString());
        }
    }
}
