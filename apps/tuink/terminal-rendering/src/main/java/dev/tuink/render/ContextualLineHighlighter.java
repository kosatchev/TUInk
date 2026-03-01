package dev.tuink.render;

import dev.tuink.core.TextModel;
import java.util.List;

/**
 * Optional highlighter contract when syntax rules depend on document context.
 */
public interface ContextualLineHighlighter extends LineHighlighter {
    List<StyleSpan> highlight(TextModel model, int lineIndex, String line);
}
