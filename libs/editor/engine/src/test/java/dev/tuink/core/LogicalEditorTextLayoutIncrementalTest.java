package dev.tuink.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

class LogicalEditorTextLayoutIncrementalTest {
    @Test
    void cachedLogicalColumnsAndHitTestingMatchFreshLayoutsAfterLocalTabAndUnicodeEdits() {
        TextModel model = new TextModel(new SimpleLineBuffer());
        model.replaceLines(List.of("a\tя😀é\ttext"));
        LogicalEditorTextLayout layout = new LogicalEditorTextLayout(model);
        Random random = new Random(92_873L);
        for (int attempt = 0; attempt < 100; attempt++) {
            int tabWidth = 4;
            assertEquals(fresh(model).visualWidth(0, tabWidth), layout.visualWidth(0, tabWidth));
            int position = random.nextInt(model.lineLength(0) + 1);
            LineText line = model.lineText(0);
            if (position > 0 && position < line.length() && Character.isHighSurrogate(line.charAt(position - 1))
                && Character.isLowSurrogate(line.charAt(position))) position--;
            if (attempt % 3 == 0 && position < line.length()) model.deleteForward(new Position(0, position));
            else model.insertChar(new Position(0, position), "x\tя́".charAt(random.nextInt(4)));
            LogicalEditorTextLayout expected = fresh(model);
            int width = expected.visualWidth(0, tabWidth);
            assertEquals(width, layout.visualWidth(0, tabWidth), "width " + attempt);
            for (int offset = 0; offset <= model.lineLength(0); offset++) {
                assertEquals(expected.visualColumnAt(0, offset, tabWidth), layout.visualColumnAt(0, offset, tabWidth));
            }
            for (int visual = 0; visual <= width; visual++) {
                for (EditorTextLayout.Bias bias : EditorTextLayout.Bias.values()) {
                    assertEquals(expected.textOffsetAtVisualColumn(0, visual, tabWidth, bias),
                        layout.textOffsetAtVisualColumn(0, visual, tabWidth, bias), "hit " + attempt);
                }
            }
            assertEquals(expected.wrapStarts(0, 7, true, tabWidth), layout.wrapStarts(0, 7, true, tabWidth));
        }
    }

    private static LogicalEditorTextLayout fresh(TextModel model) {
        TextModel copy = new TextModel(new SimpleLineBuffer());
        copy.replaceLines(model.snapshotLines(), model.fileMetadata());
        return new LogicalEditorTextLayout(copy);
    }
}
