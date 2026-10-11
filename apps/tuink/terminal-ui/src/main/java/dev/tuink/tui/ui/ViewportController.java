package dev.tuink.tui.ui;

import dev.tuink.core.EditorEngine;
import dev.tuink.core.EditorState;
import dev.tuink.core.EditorTextLayout;
import dev.tuink.core.EditorViewportBehavior;
import dev.tuink.core.Position;
import dev.tuink.core.TextModel;
import dev.tuink.tui.command.TuinkCommand;
import dev.tuink.tui.input.EditorInput;
import dev.tuiframe.input.PasteEvent;
import dev.tuiframe.input.TextInput;
import dev.tuiframe.scroll.ScrollInteraction;
import java.util.Collections;
import java.util.List;

/**
 * Tracks viewport interaction mode and applies cursor visibility policy.
 */
public final class ViewportController {
    private boolean manualHorizontalPan;
    private boolean manualVerticalPan;
    private ViewportSnapshot previousViewport;

    public void onInput(EditorInput input, boolean selectionPresent) {
        onInput(input, selectionPresent, true);
    }

    public void onInput(EditorInput input, boolean selectionPresent, boolean documentEditingAllowed) {
        TuinkCommand command = input.command();
        boolean textInput = input.event() instanceof TextInput
            || input.event() instanceof PasteEvent paste && !paste.truncated() && !paste.text().isEmpty();
        if (!documentEditingAllowed && (command == null ? textInput : command.editsDocumentText())) {
            return;
        }
        if (command == null ? !textInput : !interactsWithDocumentText(command)) {
            return;
        }
        manualHorizontalPan = false;
        manualVerticalPan = selectionPresent && (
            command == TuinkCommand.CURSOR_PAGE_UP || command == TuinkCommand.CURSOR_PAGE_DOWN
        );
    }

    private static boolean interactsWithDocumentText(TuinkCommand command) {
        // Saving, copying and UI commands leave a manually chosen reading position intact.
        return switch (command) {
            case EDIT_UNDO, EDIT_REDO, EDIT_CUT, EDIT_PASTE, EDIT_SELECT_ALL,
                EDIT_TOGGLE_LINE_COMMENT, EDIT_INSERT_TAB, EDIT_INSERT_NEWLINE,
                EDIT_BACKSPACE, EDIT_DELETE_FORWARD,
                CURSOR_UP, CURSOR_DOWN, CURSOR_LEFT, CURSOR_RIGHT,
                CURSOR_SELECT_UP, CURSOR_SELECT_DOWN, CURSOR_SELECT_LEFT, CURSOR_SELECT_RIGHT,
                CURSOR_WORD_LEFT, CURSOR_WORD_RIGHT, CURSOR_PAGE_UP, CURSOR_PAGE_DOWN,
                CURSOR_HOME, CURSOR_END,
                SEARCH_NEXT, SEARCH_PREVIOUS, SEARCH_REPLACE, SEARCH_REPLACE_ALL -> true;
            default -> false;
        };
    }

    public void onManualVerticalPan() {
        manualVerticalPan = true;
    }

    public void onEditorTextInteraction() {
        // Returning focus to the text area should restore cursor-follow behavior.
        manualHorizontalPan = false;
        manualVerticalPan = false;
    }

    public void onScrollbarInteraction(
        EditorView.ScrollPointerResult scrollPointer,
        int leftVisualBefore,
        int leftVisualAfter,
        int topLineBefore,
        int topLineAfter
    ) {
        if (scrollPointer == null || !scrollPointer.consumed()) {
            return;
        }
        if (scrollPointer.dragAxis() == EditorView.ScrollAxis.HORIZONTAL || leftVisualAfter != leftVisualBefore) {
            manualHorizontalPan = true;
        }
        if (scrollPointer.dragAxis() == EditorView.ScrollAxis.VERTICAL || topLineAfter != topLineBefore) {
            manualVerticalPan = true;
        }
    }

    public void ensureCursorVisible(
        EditorEngine engine,
        int contentRows,
        int contentCols,
        boolean wrapLines,
        boolean wrapByWords
    ) {
        ensureCursorVisible(engine, contentRows, contentCols, wrapLines, wrapByWords, 4);
    }

    public void ensureCursorVisible(
        EditorEngine engine,
        int contentRows,
        int contentCols,
        boolean wrapLines,
        boolean wrapByWords,
        int tabWidth
    ) {
        // A hidden editor has no new visual geometry. Keep the last visible
        // layout so its text anchor survives a zero-sized intermediate frame.
        if (contentRows <= 0 || contentCols <= 0) {
            return;
        }
        TextModel model = engine.model();
        EditorState state = engine.state();
        EditorTextLayout layout = engine.textLayout();
        int safeTabWidth = tabWidth <= 2 ? 2 : 4;
        ViewportGeometry geometry = new ViewportGeometry(contentCols, wrapLines, wrapByWords, safeTabWidth);
        Position anchor = null;
        if (previousViewport != null && previousViewport.model() == model && previousViewport.state() == state) {
            // Mouse scrolling changes state between render passes. Read that
            // position using the geometry in which the gesture took place.
            anchor = previousViewport.revision() == model.revision() && previousViewport.top().equals(currentTop(state))
                ? previousViewport.anchor()
                : textAnchor(model, layout, state, previousViewport.geometry());
            if (!geometry.equals(previousViewport.geometry())) {
                EditorViewportBehavior.applyWrapTop(state, topForAnchor(model, layout, anchor, geometry));
            }
        }
        if (!manualVerticalPan) {
            engine.ensureCursorVisible(
                contentRows,
                wrapLines ? contentCols : (manualHorizontalPan ? -1 : contentCols),
                wrapLines,
                wrapByWords
            );
        }
        if (wrapLines) {
            EditorViewportBehavior.WrapTop top = EditorViewportBehavior.normalizeWrapTop(
                model, layout, state.getTopLine(), state.getTopWrapSegment(),
                contentCols, wrapByWords, safeTabWidth
            );
            int offset = EditorViewportBehavior.wrappedOffsetForWrapTop(
                model, layout, top, contentCols, wrapByWords, safeTabWidth
            );
            int total = layout.totalWrappedRows(contentCols, wrapByWords, safeTabWidth);
            int bounded = ScrollInteraction.clampOffset(offset, contentRows, total);
            EditorViewportBehavior.applyWrapTop(
                state,
                bounded == offset ? top : EditorViewportBehavior.wrapTopFromWrappedOffset(
                    model, layout, bounded, contentCols, wrapByWords, safeTabWidth
                )
            );
            state.setLeftColumn(0);
            state.setLeftVisualColumn(0);
            rememberViewport(model, layout, state, geometry, anchor);
            return;
        }

        state.setTopLine(ScrollInteraction.clampOffset(state.getTopLine(), contentRows, model.lineCount()));
        state.setTopWrapSegment(0);
        Position cursor = model.clamp(state.getCursor());
        int requiredColumns = Math.max(
            layout.maxVisualLineWidth(safeTabWidth),
            layout.visualColumnAt(cursor.line(), cursor.col(), safeTabWidth) + 1
        );
        int left = ScrollInteraction.clampOffset(state.getLeftVisualColumn(), contentCols, requiredColumns);
        if (left != state.getLeftVisualColumn()) {
            EditorViewportBehavior.applyHorizontalScrollVisual(state, model, layout, left, safeTabWidth);
        }
        rememberViewport(model, layout, state, geometry, anchor);
    }

    private void rememberViewport(
        TextModel model,
        EditorTextLayout layout,
        EditorState state,
        ViewportGeometry geometry,
        Position anchor
    ) {
        EditorViewportBehavior.WrapTop top = currentTop(state);
        // Keep the original text offset when a wider row starts before it.
        // Repeated resizes can then return to the same text without drift.
        // Cursor following and EOF clamping establish a new visible anchor.
        if (anchor == null || !top.equals(topForAnchor(model, layout, anchor, geometry))) {
            anchor = textAnchor(model, layout, state, geometry);
        }
        previousViewport = new ViewportSnapshot(model, state, model.revision(), geometry, top, anchor);
    }

    private static Position textAnchor(
        TextModel model,
        EditorTextLayout layout,
        EditorState state,
        ViewportGeometry geometry
    ) {
        if (!geometry.wrapLines()) {
            return model.clamp(new Position(state.getTopLine(), 0));
        }
        EditorViewportBehavior.WrapTop top = EditorViewportBehavior.normalizeWrapTop(
            model, layout, state.getTopLine(), state.getTopWrapSegment(),
            geometry.columns(), geometry.wrapByWords(), geometry.tabWidth()
        );
        int offset = layout.wrapStarts(
            top.line(), geometry.columns(), geometry.wrapByWords(), geometry.tabWidth()
        ).get(top.segmentOffset());
        return new Position(top.line(), offset);
    }

    private static EditorViewportBehavior.WrapTop topForAnchor(
        TextModel model,
        EditorTextLayout layout,
        Position anchor,
        ViewportGeometry geometry
    ) {
        Position bounded = model.clamp(anchor);
        if (!geometry.wrapLines()) {
            return new EditorViewportBehavior.WrapTop(bounded.line(), 0);
        }
        List<Integer> starts = layout.wrapStarts(
            bounded.line(), geometry.columns(), geometry.wrapByWords(), geometry.tabWidth()
        );
        int index = Collections.binarySearch(starts, bounded.col());
        int segment = index >= 0 ? index : Math.max(0, -index - 2);
        return new EditorViewportBehavior.WrapTop(bounded.line(), segment);
    }

    private static EditorViewportBehavior.WrapTop currentTop(EditorState state) {
        return new EditorViewportBehavior.WrapTop(state.getTopLine(), state.getTopWrapSegment());
    }

    private record ViewportGeometry(int columns, boolean wrapLines, boolean wrapByWords, int tabWidth) {
    }

    private record ViewportSnapshot(
        TextModel model,
        EditorState state,
        long revision,
        ViewportGeometry geometry,
        EditorViewportBehavior.WrapTop top,
        Position anchor
    ) {
    }
}
