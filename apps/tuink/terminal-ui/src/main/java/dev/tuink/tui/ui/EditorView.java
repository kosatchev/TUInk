package dev.tuink.tui.ui;

import dev.tuink.core.EditorEngine;
import dev.tuink.editor.command.DocumentCommand;
import dev.tuink.editor.command.DocumentCommandResult;
import dev.tuink.editor.document.TextPosition;
import dev.tuink.core.EditorState;
import dev.tuink.core.EditorTextLayout;
import dev.tuink.core.Position;
import dev.tuink.core.TextModel;
import dev.tuink.render.EditorViewCoordinates;
import dev.tuink.render.EditorViewNavigation;
import dev.tuink.render.surface.TextSurfaceInteraction;
import dev.tuink.render.surface.TextSurfacePointer;
import dev.tuiframe.geometry.Rect;
import dev.tuiframe.scroll.ScrollLayout;
import dev.tuink.render.RenderPlan;
import dev.tuiframe.geometry.Point;
import dev.tuiframe.input.PointerEvent;
import dev.tuiframe.scroll.Scrollbar;
import java.util.Objects;
import java.util.function.LongSupplier;
import java.util.function.Function;

/**
 * UI helper for the main editor text area.
 */
public final class EditorView {
    private final LongSupplier nanoClock;
    private Function<DocumentCommand, DocumentCommandResult.Applied> commands;

    public enum ScrollAxis {
        NONE,
        VERTICAL,
        HORIZONTAL
    }

    private final TextSurfacePointer<Position> pointer = new TextSurfacePointer<>((first, second) ->
        first.line() == second.line() && Math.abs((long) first.col() - second.col()) <= 1);
    private int tabWidth = 4;

    public EditorView() {
        this(System::nanoTime);
    }

    public EditorView(LongSupplier nanoClock) {
        this.nanoClock = Objects.requireNonNull(nanoClock, "nanoClock");
    }

    /** Binds pointer actions to the window's current registered document view. */
    public void bindCommands(Function<DocumentCommand, DocumentCommandResult.Applied> commands) {
        this.commands = Objects.requireNonNull(commands, "commands");
    }

    private boolean select(DocumentCommand.SelectionAction action, Position position) {
        if (commands == null) throw new IllegalStateException("Pointer commands require a registered document view");
        return commands.apply(new DocumentCommand.Select(action, position == null ? null
            : new TextPosition(position.line(), position.col()), null)).performed();
    }

    public CursorPosition cursorFromPlan(RenderPlan plan, int contentTopRow) {
        int top = plan.scrollLayout().contentBounds().y();
        return new CursorPosition(plan.cursorRow() + top + 1, plan.cursorCol() + 1);
    }

    public void cancelMouseSelection() {
        pointer.cancel();
    }

    public void setTabWidth(int tabWidth) {
        this.tabWidth = tabWidth <= 2 ? 2 : 4;
    }

    public boolean handleMousePress(
        PointerEvent mouse,
        TextModel model,
        EditorState state,
        RenderPlan plan,
        int contentRows,
        EditorEngine engine,
        int contentTopRow,
        boolean extendSelection
    ) {
        var placement = pointerPlacement(engine.textLayout(), state, plan, contentRows, contentTopRow);
        var result = pointer.handle(placement, mouse, extendSelection, nanoClock.getAsLong());
        boolean word = applyPointer(result, state, engine, plan, contentRows);
        if (result.target() == null) ensureCursorVisible(engine, plan, contentRows);
        return word;
    }

    public void handleMouseDrag(
        PointerEvent mouse, TextModel model, EditorState state, RenderPlan plan, int contentRows,
        EditorEngine engine, int contentTopRow
    ) {
        if (!pointer.selecting() || mouse.button() != PointerEvent.Button.LEFT) return;
        dragSelection(mouse, state, plan, contentRows, engine, contentTopRow, nanoClock.getAsLong());
    }

    public long nanosUntilSelectionScroll(long nowNanos) { return pointer.nanosUntilRepeat(nowNanos); }

    public boolean scrollSelectionIfDue(
        TextModel model, EditorState state, RenderPlan plan, int contentRows,
        EditorEngine engine, int contentTopRow, long nowNanos
    ) {
        PointerEvent repeat = pointer.repeatIfDue(nowNanos);
        return repeat != null && dragSelection(repeat, state, plan, contentRows, engine, contentTopRow, nowNanos);
    }

    private boolean dragSelection(PointerEvent mouse, EditorState state, RenderPlan plan, int contentRows,
                                  EditorEngine engine, int contentTopRow, long now) {
        Position before = state.getCursor();
        int top = state.getTopLine(), segment = state.getTopWrapSegment(), left = state.getLeftVisualColumn();
        var placement = pointerPlacement(engine.textLayout(), state, plan, contentRows, contentTopRow);
        var result = pointer.handle(placement, mouse, false, now);
        applyPointer(result, state, engine, plan, contentRows);
        boolean changed = !before.equals(state.getCursor()) || top != state.getTopLine()
            || segment != state.getTopWrapSegment() || left != state.getLeftVisualColumn();
        pointer.afterDrag(placement, mouse, changed, now);
        return changed;
    }

    private TextSurfacePointer.Placement<Position> pointerPlacement(
        EditorTextLayout layout, EditorState state, RenderPlan plan, int contentRows, int contentTopRow
    ) {
        int columns = plan.scrollLayout().contentBounds().width();
        int top = plan.scrollLayout().contentBounds().y();
        var content = new Rect(plan.scrollLayout().contentBounds().x(), top, columns, Math.max(0, contentRows));
        var placement = new ScrollLayout(content, content, null, null, null);
        var geometry = coordinates(layout, plan, columns);
        int pointerRows = Math.max(0, contentRows);
        Rect horizontalTrack = plan.scrollLayout().horizontalScrollbarBounds();
        if (horizontalTrack != null) pointerRows = Math.max(pointerRows, Math.max(0, horizontalTrack.bottomExclusive() - top));
        return new TextSurfacePointer.Placement<>(new Rect(plan.editorBounds().x(), top, plan.editorBounds().width(), pointerRows),
            placement, null, null, geometry, geometry.viewport(state, contentRows, columns),
            geometry.totalRows(), plan.totalColumns(), TextSurfacePointer.Policy.source(plan.wrapLines()));
    }

    /** One production entry: the shared pointer controller owns both content and scrollbar capture. */
    public PointerResult handlePointer(PointerEvent mouse, EditorEngine engine, RenderPlan plan,
                                       int rows, int columns, int contentRows, int contentTopRow, boolean extendSelection) {
        EditorState state = engine.state();
        var bars = EditorScrollbars.from(plan, rows, columns, contentTopRow);
        var geometry = coordinates(engine.textLayout(), plan, bars.layout().contentBounds().width());
        int top = bars.layout().contentBounds().y();
        int pointerRows = Math.max(0, contentRows);
        Rect horizontal = bars.layout().horizontalScrollbarBounds();
        if (horizontal != null) pointerRows = Math.max(pointerRows, Math.max(0, horizontal.bottomExclusive() - top));
        var surface = plan.editorBounds().intersection(new Rect(0, top, Math.max(0, columns), pointerRows));
        var placement = new TextSurfacePointer.Placement<Position>(surface,
            bars.layout(), bars.vertical(), bars.horizontal(), geometry,
            geometry.viewport(state, bars.layout().contentBounds().height(), bars.layout().contentBounds().width()),
            plan.totalLines(), plan.totalColumns(), TextSurfacePointer.Policy.source(plan.wrapLines()));
        Position cursorBefore = state.getCursor();
        int topBefore = state.getTopLine(), segmentBefore = state.getTopWrapSegment(), leftBefore = state.getLeftVisualColumn();
        long now = nanoClock.getAsLong();
        var gesture = pointer.handle(placement, mouse, extendSelection, now);
        boolean word = applyPointer(gesture, state, engine, plan, contentRows);
        boolean textInteraction = gesture.action() == TextSurfacePointer.Action.BEGIN
            || gesture.action() == TextSurfacePointer.Action.EXTEND || gesture.action() == TextSurfacePointer.Action.WORD;
        if (textInteraction && (mouse.kind() == PointerEvent.Kind.DRAG || mouse.kind() == PointerEvent.Kind.MOVE)) {
            boolean changed = !cursorBefore.equals(state.getCursor()) || topBefore != state.getTopLine()
                || segmentBefore != state.getTopWrapSegment() || leftBefore != state.getLeftVisualColumn();
            pointer.afterDrag(placement, mouse, changed, now);
        }
        boolean scroll = gesture.action() == TextSurfacePointer.Action.SCROLL;
        boolean wheel = mouse.kind() == PointerEvent.Kind.SCROLL_UP || mouse.kind() == PointerEvent.Kind.SCROLL_DOWN
            || mouse.kind() == PointerEvent.Kind.SCROLL_LEFT || mouse.kind() == PointerEvent.Kind.SCROLL_RIGHT;
        ScrollAxis axis = !scroll || wheel || gesture.drag() == null ? ScrollAxis.NONE
            : gesture.drag().axis() == Scrollbar.Axis.VERTICAL ? ScrollAxis.VERTICAL : ScrollAxis.HORIZONTAL;
        var scrollResult = new ScrollPointerResult(scroll, axis, gesture.drag() == null ? 0 : gesture.drag().grabOffset());
        return new PointerResult(gesture.consumed(), textInteraction,
            textInteraction && mouse.kind() == PointerEvent.Kind.PRESS, word, scrollResult);
    }

    public boolean pointerCaptureActive() { return pointer.capturing(); }

    public record PointerResult(boolean consumed, boolean textInteraction, boolean focusDocument,
                                boolean selectedWord, ScrollPointerResult scroll) {}

    /** The window keeps focus/manual-pan policy; wheel classification and offset computation are shared. */
    public boolean handleWheel(PointerEvent mouse, EditorTextLayout layout, EditorState state,
                               RenderPlan plan, int contentRows, int contentTopRow) {
        var placement = pointerPlacement(layout, state, plan, contentRows, contentTopRow);
        var gesture = pointer.handle(placement, mouse, false, nanoClock.getAsLong());
        if (gesture.action() != TextSurfacePointer.Action.SCROLL) return false;
        var geometry = coordinates(layout, plan, placement.viewport().columns());
        if (placement.viewport().top() != gesture.viewport().top()) geometry.applyTop(state, geometry.topAtRow(gesture.viewport().top()));
        if (!plan.wrapLines() && placement.viewport().left() != gesture.viewport().left())
            geometry.applyHorizontalOffset(state, gesture.viewport().left());
        return gesture.consumed();
    }

    private boolean applyPointer(TextSurfacePointer.Result<Position> result, EditorState state,
                                 EditorEngine engine, RenderPlan plan, int contentRows) {
        var coordinates = coordinates(engine.textLayout(), plan, result.viewport().columns());
        var previous = coordinates.viewport(state, contentRows, result.viewport().columns());
        if (previous.top() != result.viewport().top()) coordinates.applyTop(state, coordinates.topAtRow(result.viewport().top()));
        if (previous.left() != result.viewport().left()) coordinates.applyHorizontalOffset(state, result.viewport().left());
        boolean selectedWord = false;
        switch (result.action()) {
            case BEGIN -> select(DocumentCommand.SelectionAction.BEGIN, result.target());
            case EXTEND -> select(DocumentCommand.SelectionAction.EXTEND, result.target());
            case WORD -> {
                selectedWord = select(DocumentCommand.SelectionAction.WORD, result.target());
                if (!selectedWord) select(DocumentCommand.SelectionAction.BEGIN, result.target());
            }
            case FINISH -> select(DocumentCommand.SelectionAction.FINISH, null);
            default -> { }
        }
        if (result.target() != null) ensureCursorVisible(engine, plan, contentRows);
        return selectedWord;
    }

    private void ensureCursorVisible(EditorEngine engine, RenderPlan plan, int contentRows) {
        int contentColumns = plan.scrollLayout().contentBounds().width();
        EditorViewNavigation.ensureCursorVisible(engine, contentRows, contentColumns, plan.wrapLines(), plan.wrapByWords());
    }

    public void handleMouseRelease(EditorEngine engine) {
        if (pointer.finishSelection()) select(DocumentCommand.SelectionAction.FINISH, null);
    }

    public boolean isMouseSelecting() { return pointer.selecting(); }

    public Position mouseToTextPosition(
        PointerEvent mouse,
        TextModel model,
        EditorTextLayout textLayout,
        EditorState state,
        RenderPlan plan,
        int contentRows,
        int contentTopRow
    ) {
        if (textLayout.model() != model) {
            throw new IllegalArgumentException("textLayout must be bound to model");
        }
        int columns = plan.scrollLayout().contentBounds().width();
        return coordinates(textLayout, plan, columns).positionInViewport(state, contentRows,
            mouse.row() - plan.scrollLayout().contentBounds().y(),
            mouse.column() - plan.scrollLayout().contentBounds().x());
    }

    private EditorViewCoordinates coordinates(EditorTextLayout layout, RenderPlan plan, int columns) {
        return new EditorViewCoordinates(layout, columns, plan.wrapLines(), plan.wrapByWords(), tabWidth);
    }

    public ScrollPointerResult handleScrollbarPointer(
        int mouseRow,
        int mouseCol,
        int rows,
        int cols,
        int contentTopRow,
        RenderPlan plan,
        TextModel model,
        EditorTextLayout textLayout,
        EditorState state
    ) {
        return handleScrollbarPointer(
            mouseRow,
            mouseCol,
            rows,
            cols,
            contentTopRow,
            plan,
            model,
            textLayout,
            state,
            ScrollAxis.NONE,
            0
        );
    }

    public ScrollPointerResult handleScrollbarPointer(
        int mouseRow,
        int mouseCol,
        int rows,
        int cols,
        int contentTopRow,
        RenderPlan plan,
        TextModel model,
        EditorTextLayout textLayout,
        EditorState state,
        ScrollAxis axis
    ) {
        return handleScrollbarPointer(
            mouseRow,
            mouseCol,
            rows,
            cols,
            contentTopRow,
            plan,
            model,
            textLayout,
            state,
            axis,
            0
        );
    }

    public ScrollPointerResult handleScrollbarPointer(
        int mouseRow,
        int mouseCol,
        int rows,
        int cols,
        int contentTopRow,
        RenderPlan plan,
        TextModel model,
        EditorTextLayout textLayout,
        EditorState state,
        ScrollAxis axis,
        int dragThumbOffset
    ) {
        if (textLayout.model() != model) throw new IllegalArgumentException("textLayout must be bound to model");
        EditorScrollbars bars = EditorScrollbars.from(plan, rows, cols, contentTopRow);
        int columns = bars.layout().contentBounds().width();
        var geometry = coordinates(textLayout, plan, columns);
        var placement = new TextSurfacePointer.Placement<Position>(bars.layout().contentBounds(), bars.layout(),
            bars.vertical(), bars.horizontal(), geometry, geometry.viewport(state, bars.layout().contentBounds().height(), columns),
            plan.totalLines(), plan.totalColumns(), TextSurfacePointer.Policy.source(plan.wrapLines()));
        Scrollbar.Drag drag = axis == ScrollAxis.NONE ? null : new Scrollbar.Drag(
            axis == ScrollAxis.VERTICAL ? Scrollbar.Axis.VERTICAL : Scrollbar.Axis.HORIZONTAL, dragThumbOffset);
        var result = pointer.scrollbar(placement, new Point(mouseCol, mouseRow), drag);
        if (result.consumed()) {
            if (placement.viewport().top() != result.viewport().top()) geometry.applyTop(state, geometry.topAtRow(result.viewport().top()));
            if (placement.viewport().left() != result.viewport().left()) geometry.applyHorizontalOffset(state, result.viewport().left());
        }
        ScrollAxis nextAxis = result.drag() == null ? ScrollAxis.NONE
            : result.drag().axis() == Scrollbar.Axis.VERTICAL ? ScrollAxis.VERTICAL : ScrollAxis.HORIZONTAL;
        return new ScrollPointerResult(result.consumed(), nextAxis, result.drag() == null ? 0 : result.drag().grabOffset());
    }

    public record CursorPosition(int row, int col) {
    }

    public record ScrollPointerResult(boolean consumed, ScrollAxis dragAxis, int dragThumbOffset) {
    }

}
