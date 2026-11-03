package dev.tuink.tui;

import dev.tuink.editor.storage.BomKind;
import java.util.List;
import static dev.tuink.tui.StatusBarLayout.Field;
import static dev.tuink.tui.StatusBarLayout.Key;

/** Preview supplies reading fields to the same footer layout as Source. */
final class MarkdownPreviewStatus {
    private MarkdownPreviewStatus() {}

    static String text(MarkdownPreviewState.Placement preview, boolean snapshot,
        String encoding, BomKind bom, int tabIndex, int tabCount, int columns) {
        String mode = snapshot ? "snapshot" : "live";
        String position = "", compactPosition = "";
        if (preview.caret() != null && preview.result() != null) {
            position = "Row " + (preview.caret().row() + 1) + "/" + preview.result().lines().size()
                + " Col " + (preview.caret().column() + 1);
            compactPosition = (preview.caret().row() + 1) + ":" + (preview.caret().column() + 1);
        }
        String searchCount = preview.searchQuery().isEmpty() ? "" : "Find " + (preview.activeMatch() + 1)
            + "/" + preview.searchMatches().size();
        String search = searchCount.isEmpty() ? "" : searchCount + " " + preview.searchQuery();
        return StatusBarLayout.place(List.of(
            Field.required(Key.POSITION, position, compactPosition, 70),
            Field.required(Key.TABS, "Tab " + (tabIndex + 1) + "/" + tabCount, "Tab " + (tabIndex + 1) + "/" + tabCount, 80),
            Field.required(Key.SWITCH, "Ctrl+P: Source", "Ctrl+P", 100, 20),
            Field.optional(Key.DETAIL, search, searchCount, 5, 0),
            Field.optional(Key.PROGRESS, preview.loading() ? "updating…" : "", 85)), List.of(
            Field.required(Key.MODE, "Preview (" + mode + ")", "MD:" + mode, 90, 10),
            Field.optional(Key.ENCODING, "ENC:" + encoding + (bom == BomKind.NONE ? "" : "+BOM"), 60)),
            columns, 0).text();
    }
}
