package dev.tuiframe.widget;

import dev.tuiframe.cell.CellText;
import dev.tuiframe.cell.Canvas;
import dev.tuiframe.geometry.Point;
import dev.tuiframe.geometry.Rect;
import dev.tuiframe.geometry.Size;
import dev.tuiframe.input.KeyStroke;
import dev.tuiframe.input.PasteEvent;
import dev.tuiframe.input.PointerEvent;
import dev.tuiframe.input.TextInput;
import dev.tuiframe.input.TuiInputEvent;
import dev.tuiframe.style.CellStyle;
import dev.tuiframe.text.TerminalText;
import java.util.Objects;
import java.util.Optional;

/**
 * Reusable single-line text input buffer and interactive TUI widget.
 *
 * <p>The field owns its value, grapheme-boundary cursor and selection. Focus,
 * submission policy, validation, and application commands remain external.
 * The {@link #insert(String)} and {@link #set(String)} methods
 * accept trusted application text unchanged, including tabs and controls.
 * Raw {@link TextInput} is printable by contract, while raw paste is
 * normalized to safe single-line content before insertion.</p>
 *
 * <p>During mouse selection, route {@link #captures(PointerEvent) captured}
 * drags and releases before ordinary hit testing. Cancel that capture with
 * {@link #cancelMouseSelection()} when the active focus or surface changes.
 * A geometry change and keyboard interaction also cancel capture. Cancelling
 * capture preserves the visible text window; changes to the value, caret or
 * selection resume the normal caret-following layout.</p>
 */
public final class TextField implements InteractiveWidget<TextField.Action> {
    private static final int TAB_WIDTH = 4;
    private static final TerminalText.Metrics EMPTY_METRICS = TerminalText.metrics("", TAB_WIDTH);
    private static final InteractionResult<Action> VALUE_CHANGED_RESULT =
        InteractionResult.output(Action.VALUE_CHANGED);
    private static final InteractionResult<Action> SUBMITTED_RESULT =
        InteractionResult.output(Action.SUBMITTED);
    private static final InteractionResult<Action> PASTE_REJECTED_RESULT =
        InteractionResult.output(Action.PASTE_REJECTED);
    private static final InteractionResult<Action> FOCUS_SELF_RESULT =
        InteractionResult.focus(FocusRequest.SELF);

    private final StringBuilder value = new StringBuilder();
    private int cursor;
    private int selectionAnchor = -1;
    private boolean mouseSelecting;
    private Rect mouseViewportBounds;
    private int mouseViewportLeft;
    private TerminalText.Metrics cachedMetrics = EMPTY_METRICS;
    private boolean metricsValid = true;

    public void insert(String text) {
        insertValue(text);
    }

    /** Inserts clipboard text at the caret using the same single-line policy as raw paste. */
    public void paste(String text) {
        pasteValue(text);
    }

    public void backspace() {
        backspaceValue();
    }

    public void deleteForward() {
        deleteForwardValue();
    }

    public void moveLeft() {
        moveLeftValue();
    }

    public void moveRight() {
        moveRightValue();
    }

    public void moveHome() {
        moveHomeValue();
    }

    public void moveEnd() {
        moveEndValue();
    }

    public void clear() {
        cancelMouseSelection();
        if (!value.isEmpty()) {
            value.setLength(0);
            invalidateMetrics();
        }
        cursor = 0;
        selectionAnchor = -1;
    }

    public void set(String text) {
        cancelMouseSelection();
        String next = text == null ? "" : text;
        if (!metrics().text().equals(next)) {
            value.setLength(0);
            value.append(next);
            invalidateMetrics();
        }
        updateCursor(value.length(), -1);
    }

    public boolean isEmpty() {
        return value.isEmpty();
    }

    public int length() {
        return value.length();
    }

    /** Returns the cursor as a UTF-16 offset on an extended-grapheme boundary. */
    public int cursor() {
        return cursor;
    }

    public boolean hasSelection() {
        return selectionAnchor >= 0 && selectionAnchor != cursor;
    }

    public int selectionStart() {
        return hasSelection() ? Math.min(selectionAnchor, cursor) : cursor;
    }

    public int selectionEnd() {
        return hasSelection() ? Math.max(selectionAnchor, cursor) : cursor;
    }

    public String selectedText() {
        return text().substring(selectionStart(), selectionEnd());
    }

    public void selectAll() {
        cancelMouseSelection();
        updateCursor(value.length(), 0);
    }

    public boolean mouseSelecting() {
        return mouseSelecting;
    }

    /** Cancels pointer capture without moving the caret, selection or visible text window. */
    public void cancelMouseSelection() {
        mouseSelecting = false;
    }

    /** Captured pointer events must reach the field before ordinary hit testing. */
    public boolean captures(PointerEvent pointer) {
        Objects.requireNonNull(pointer, "pointer");
        return mouseSelecting && (pointer.kind() == PointerEvent.Kind.RELEASE
            || pointer.kind() == PointerEvent.Kind.DRAG && pointer.button() == PointerEvent.Button.LEFT);
    }

    /** Caret and editing keys stay with a focused field even for unsupported modifiers. */
    public static boolean ownsEditingKey(KeyStroke stroke) {
        Objects.requireNonNull(stroke, "stroke");
        return switch (stroke.key()) {
            case ARROW_LEFT, ARROW_RIGHT, ARROW_UP, ARROW_DOWN, HOME, END, BACKSPACE, DELETE -> true;
            case A -> stroke.has(KeyStroke.Modifier.CTRL);
            case B, F -> stroke.has(KeyStroke.Modifier.ALT);
            case PAGE_UP, PAGE_DOWN -> stroke.has(KeyStroke.Modifier.SHIFT);
            default -> false;
        };
    }

    public boolean deleteSelection() {
        cancelMouseSelection();
        if (!hasSelection()) {
            return false;
        }
        int start = selectionStart();
        value.delete(start, selectionEnd());
        invalidateMetrics();
        cursor = metrics().floorBoundary(start);
        selectionAnchor = -1;
        return true;
    }

    /** Moves the field caret without crossing an extended grapheme boundary. */
    public void navigate(KeyStroke.Key key, boolean byWord, boolean selecting) {
        cancelMouseSelection();
        int target = switch (key) {
            case ARROW_LEFT -> !selecting && hasSelection() ? selectionStart()
                : byWord ? previousWord() : metrics().previousBoundary(cursor);
            case ARROW_RIGHT -> !selecting && hasSelection() ? selectionEnd()
                : byWord ? nextWord() : metrics().nextBoundary(cursor);
            case HOME -> 0;
            case END -> value.length();
            default -> cursor;
        };
        moveCursor(target, selecting);
    }

    public int cursorCellColumn() {
        return metrics().visualColumnAt(cursor);
    }

    public int displayWidth() {
        return metrics().visualWidth();
    }

    public String text() {
        return metrics().text();
    }

    @Override
    public Size measure(Size available) {
        Objects.requireNonNull(available, "available");
        long desiredWidth = Math.max(1L, (long) metrics().visualWidth() + 1L);
        return new Size(
            (int) Math.min(available.width(), Math.min(Integer.MAX_VALUE, desiredWidth)),
            Math.min(1, available.height())
        );
    }

    @Override
    public Placement layout(Rect bounds) {
        Objects.requireNonNull(bounds, "bounds");
        if (mouseViewportBounds != null && !mouseViewportBounds.equals(bounds)) {
            cancelMouseSelection();
            mouseViewportBounds = null;
        }
        return new Placement(
            this,
            bounds,
            metrics(),
            cursor,
            selectionStart(),
            selectionEnd()
        );
    }

    /** Application-independent effects produced by raw field interaction. */
    public enum Action {
        VALUE_CHANGED,
        SUBMITTED,
        PASTE_REJECTED
    }

    private boolean insertValue(String text) {
        cancelMouseSelection();
        if (text == null || text.isEmpty()) {
            return false;
        }
        int insertionOffset = metrics().floorBoundary(cursor);
        if (hasSelection()) {
            insertionOffset = selectionStart();
            value.delete(insertionOffset, selectionEnd());
        }
        selectionAnchor = -1;
        value.insert(insertionOffset, text);
        invalidateMetrics();
        int tentativeCursor = insertionOffset + text.length();
        cursor = metrics().ceilBoundary(tentativeCursor);
        return true;
    }

    private boolean pasteValue(String text) {
        return insertValue(normalizeSingleLinePaste(text));
    }

    private boolean backspaceValue() {
        cancelMouseSelection();
        if (deleteSelection()) {
            return true;
        }
        selectionAnchor = -1;
        if (cursor <= 0) {
            return false;
        }
        TerminalText.Metrics before = metrics();
        int end = before.ceilBoundary(cursor);
        int start = before.previousBoundary(end);
        if (start >= end) {
            return false;
        }
        value.delete(start, end);
        invalidateMetrics();
        cursor = metrics().floorBoundary(start);
        return true;
    }

    private boolean deleteForwardValue() {
        cancelMouseSelection();
        if (deleteSelection()) {
            return true;
        }
        selectionAnchor = -1;
        if (cursor >= value.length()) {
            return false;
        }
        TerminalText.Metrics before = metrics();
        int start = before.floorBoundary(cursor);
        int end = before.nextBoundary(start);
        if (start >= end) {
            return false;
        }
        value.delete(start, end);
        invalidateMetrics();
        cursor = metrics().floorBoundary(start);
        return true;
    }

    private boolean moveLeftValue() {
        int before = cursor;
        boolean selected = hasSelection();
        navigate(KeyStroke.Key.ARROW_LEFT, false, false);
        return cursor != before || selected;
    }

    private boolean moveRightValue() {
        int before = cursor;
        boolean selected = hasSelection();
        navigate(KeyStroke.Key.ARROW_RIGHT, false, false);
        return cursor != before || selected;
    }

    private boolean moveHomeValue() {
        cancelMouseSelection();
        boolean changed = cursor != 0;
        moveCursor(0, false);
        return changed;
    }

    private boolean moveEndValue() {
        cancelMouseSelection();
        int end = value.length();
        boolean changed = cursor != end;
        moveCursor(end, false);
        return changed;
    }

    private void moveToVisualColumn(int visualColumn, boolean selecting) {
        moveCursor(metrics().offsetForVisualColumn(
            Math.max(0, visualColumn),
            TerminalText.Bias.NEAREST
        ), selecting);
    }

    private void moveCursor(int target, boolean selecting) {
        int anchor = selecting ? selectionAnchor < 0 ? cursor : selectionAnchor : -1;
        updateCursor(target, anchor);
    }

    private void updateCursor(int target, int anchor) {
        int start = anchor < 0 ? target : Math.min(anchor, target);
        int end = anchor < 0 ? target : Math.max(anchor, target);
        if (cursor != target || selectionStart() != start || selectionEnd() != end) {
            mouseViewportBounds = null;
        }
        cursor = target;
        selectionAnchor = anchor;
    }

    private int previousWord() {
        TerminalText.Metrics text = metrics();
        int offset = cursor;
        while (offset > 0 && wordClass(text.previousBoundary(offset)) == 0) {
            offset = text.previousBoundary(offset);
        }
        if (offset > 0) {
            int kind = wordClass(text.previousBoundary(offset));
            do {
                offset = text.previousBoundary(offset);
            } while (offset > 0 && wordClass(text.previousBoundary(offset)) == kind);
        }
        return offset;
    }

    private int nextWord() {
        TerminalText.Metrics text = metrics();
        int offset = cursor;
        if (offset < value.length()) {
            int kind = wordClass(offset);
            do {
                offset = text.nextBoundary(offset);
            } while (offset < value.length() && wordClass(offset) == kind);
        }
        while (offset < value.length() && wordClass(offset) == 0) {
            offset = text.nextBoundary(offset);
        }
        return offset;
    }

    private int wordClass(int offset) {
        int point = Character.codePointAt(value, offset);
        return Character.isWhitespace(point) ? 0 : Character.isLetterOrDigit(point) || point == '_' ? 1 : 2;
    }

    private boolean deleteWord(boolean backwards) {
        if (!hasSelection()) {
            int next = backwards ? previousWord() : nextWord();
            moveCursor(next, true);
        }
        return deleteSelection();
    }

    private TerminalText.Metrics metrics() {
        if (!metricsValid) {
            cachedMetrics = TerminalText.metrics(value.toString(), TAB_WIDTH);
            metricsValid = true;
        }
        return cachedMetrics;
    }

    private void invalidateMetrics() {
        metricsValid = false;
        mouseViewportBounds = null;
    }

    private static String normalizeSingleLinePaste(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        StringBuilder normalized = new StringBuilder(text.length());
        boolean separatorPending = false;
        for (int offset = 0; offset < text.length();) {
            int codePoint = text.codePointAt(offset);
            offset += Character.charCount(codePoint);
            if (codePoint == '\r' || codePoint == '\n' || codePoint == '\t') {
                separatorPending = !normalized.isEmpty();
                continue;
            }
            if (Character.isISOControl(codePoint)) {
                continue;
            }
            if (separatorPending) {
                normalized.append(' ');
                separatorPending = false;
            }
            normalized.appendCodePoint(codePoint);
        }
        return normalized.toString();
    }

    /**
     * Immutable geometry and paint snapshot for one frame.
     *
     * <p>Handling an edit mutates the owning field and makes this snapshot
     * stale; applications must layout the field again before the next paint or
     * cursor query.</p>
     */
    public static final class Placement
        implements PlacedInteractiveWidget<Action>, PlacedCursorSource {
        private final TextField owner;
        private final Rect bounds;
        private final String visibleText;
        private final int viewportLeft;
        private final int cursorColumn;
        private final int selectionLeft;
        private final int selectionRight;

        private Placement(
            TextField owner,
            Rect bounds,
            TerminalText.Metrics metrics,
            int cursorOffset,
            int selectionStart,
            int selectionEnd
        ) {
            this.owner = Objects.requireNonNull(owner, "owner");
            this.bounds = Objects.requireNonNull(bounds, "bounds");
            Objects.requireNonNull(metrics, "metrics");
            if (bounds.isEmpty()) {
                visibleText = "";
                viewportLeft = 0;
                cursorColumn = 0;
                selectionLeft = 0;
                selectionRight = 0;
                return;
            }

            int cursorVisual = metrics.visualColumnAt(cursorOffset);
            int cursorCapacity = Math.max(0, bounds.width() - 1);
            int tailLeft = Math.max(0, metrics.visualWidth() - cursorCapacity);
            int requestedLeft;
            if (bounds.equals(owner.mouseViewportBounds)) {
                requestedLeft = owner.mouseViewportLeft;
            } else if (cursorCapacity == 0) {
                requestedLeft = cursorVisual;
            } else if (cursorVisual >= tailLeft) {
                requestedLeft = tailLeft;
            } else {
                // Keyboard navigation uses deterministic pages. Mouse interaction
                // retains the visible window so a click does not move its text.
                requestedLeft = (cursorVisual / cursorCapacity) * cursorCapacity;
            }
            int startOffset = metrics.offsetForVisualColumn(
                requestedLeft,
                TerminalText.Bias.CEIL
            );
            viewportLeft = metrics.visualColumnAt(startOffset);
            cursorColumn = Math.max(
                0,
                Math.min(bounds.width() - 1, cursorVisual - viewportLeft)
            );
            visibleText = CellText.clip(metrics.text(), viewportLeft, bounds.width());
            selectionLeft = Math.max(0, metrics.visualColumnAt(selectionStart) - viewportLeft);
            selectionRight = Math.min(bounds.width(), metrics.visualColumnAt(selectionEnd) - viewportLeft);
        }

        @Override
        public Rect bounds() {
            return bounds;
        }

        @Override
        public void paint(Canvas canvas, Theme theme, WidgetState state) {
            Objects.requireNonNull(canvas, "canvas");
            Objects.requireNonNull(theme, "theme");
            Objects.requireNonNull(state, "state");
            if (bounds.isEmpty()) {
                return;
            }
            WidgetStyleRole role = !state.enabled()
                ? WidgetThemeKeys.TEXT_FIELD_DISABLED
                : state.focused()
                    ? WidgetThemeKeys.TEXT_FIELD_FOCUSED
                    : WidgetThemeKeys.TEXT_FIELD;
            CellStyle style = theme.resolveStyle(role);
            int mark = canvas.save();
            try {
                if (!canvas.clip(bounds)) {
                    return;
                }
                canvas.fill(bounds, style);
                CellText.draw(canvas, bounds.x(), bounds.y(), visibleText, style);
                if (state.enabled() && selectionRight > selectionLeft) {
                    CellStyle fallback = new CellStyle(style.bg(), style.fg(), style.bold(), true);
                    CellStyle selected = theme.resolveStyle(WidgetThemeKeys.TEXT_FIELD_SELECTION, fallback);
                    if (canvas.clip(new Rect(bounds.x() + selectionLeft, bounds.y(),
                        selectionRight - selectionLeft, 1))) {
                        canvas.fill(bounds, selected);
                        CellText.draw(canvas, bounds.x(), bounds.y(), visibleText, selected);
                    }
                }
            } finally {
                canvas.restore(mark);
            }
        }

        @Override
        public InteractionResult<Action> handle(TuiInputEvent event, FocusContext context) {
            Objects.requireNonNull(event, "event");
            Objects.requireNonNull(context, "context");
            if (!context.enabled()) {
                owner.cancelMouseSelection();
                return InteractionResult.ignored();
            }
            if (event instanceof PointerEvent pointer) {
                return handlePointer(pointer, context);
            }
            owner.cancelMouseSelection();
            if (!context.focused()) {
                return InteractionResult.ignored();
            }
            if (event instanceof TextInput text) {
                return owner.insertValue(text.text())
                    ? VALUE_CHANGED_RESULT
                    : InteractionResult.handled();
            }
            if (event instanceof PasteEvent paste) {
                if (paste.truncated()) {
                    return PASTE_REJECTED_RESULT;
                }
                return owner.pasteValue(paste.text())
                    ? VALUE_CHANGED_RESULT
                    : InteractionResult.handled();
            }
            if (!(event instanceof KeyStroke stroke)) {
                return InteractionResult.ignored();
            }
            boolean shift = stroke.has(KeyStroke.Modifier.SHIFT);
            boolean ctrl = stroke.has(KeyStroke.Modifier.CTRL);
            boolean localModifiers = !stroke.has(KeyStroke.Modifier.ALT)
                && !stroke.has(KeyStroke.Modifier.SUPER);
            if (stroke.has(KeyStroke.Modifier.ALT) && !ctrl && !stroke.has(KeyStroke.Modifier.SUPER)
                && (stroke.key() == KeyStroke.Key.B || stroke.key() == KeyStroke.Key.F)) {
                owner.navigate(stroke.key() == KeyStroke.Key.B ? KeyStroke.Key.ARROW_LEFT
                    : KeyStroke.Key.ARROW_RIGHT, true, shift);
                return InteractionResult.handled();
            }
            if (localModifiers && ctrl && !shift && stroke.key() == KeyStroke.Key.A) {
                owner.selectAll();
                return InteractionResult.handled();
            }
            if (localModifiers && switch (stroke.key()) {
                case ARROW_LEFT, ARROW_RIGHT, HOME, END -> true;
                default -> false;
            }) {
                owner.navigate(stroke.key(), ctrl, shift);
                return InteractionResult.handled();
            }
            if (localModifiers && ctrl && !shift
                && (stroke.key() == KeyStroke.Key.BACKSPACE || stroke.key() == KeyStroke.Key.DELETE)) {
                return editResult(owner.deleteWord(stroke.key() == KeyStroke.Key.BACKSPACE));
            }
            if (!stroke.modifiers().isEmpty()) {
                return ownsEditingKey(stroke) ? InteractionResult.handled() : InteractionResult.ignored();
            }
            return switch (stroke.key()) {
                case SPACE -> editResult(owner.insertValue(" "));
                case BACKSPACE -> editResult(owner.backspaceValue());
                case DELETE -> editResult(owner.deleteForwardValue());
                case ENTER -> SUBMITTED_RESULT;
                default -> ownsEditingKey(stroke) ? InteractionResult.handled() : InteractionResult.ignored();
            };
        }

        @Override
        public Optional<Point> cursorPosition(FocusContext context) {
            Objects.requireNonNull(context, "context");
            if (!context.focused() || !context.enabled() || bounds.isEmpty()) {
                return Optional.empty();
            }
            Point cursorPoint = new Point(bounds.x() + cursorColumn, bounds.y());
            return bounds.contains(cursorPoint) && context.space().parentClip().contains(cursorPoint)
                ? Optional.of(cursorPoint)
                : Optional.empty();
        }

        private InteractionResult<Action> handlePointer(
            PointerEvent pointer,
            FocusContext context
        ) {
            if (bounds.isEmpty() || !context.space().parentClip().contains(bounds)) {
                owner.cancelMouseSelection();
                return InteractionResult.ignored();
            }
            if (captures(pointer)) {
                if (!context.focused()) {
                    owner.cancelMouseSelection();
                    return InteractionResult.ignored();
                }
                if (pointer.kind() == PointerEvent.Kind.RELEASE) {
                    owner.mouseSelecting = false;
                } else {
                    // Capture keeps terminal coordinates usable beyond the parent's clip.
                    long column = (long) pointer.column() - context.space().terminalOrigin().x() - bounds.x();
                    long target = (long) viewportLeft + column;
                    owner.moveToVisualColumn((int) Math.clamp(target, 0L, Integer.MAX_VALUE), true);
                    int cursorVisual = owner.cursorCellColumn();
                    int left = viewportLeft;
                    if (cursorVisual < left) {
                        left = cursorVisual;
                    } else if ((long) cursorVisual >= (long) left + bounds.width()) {
                        left = Math.max(0, cursorVisual - bounds.width() + 1);
                    }
                    owner.mouseViewportLeft = owner.metrics().visualColumnAt(owner.metrics()
                        .offsetForVisualColumn(left, TerminalText.Bias.CEIL));
                    owner.mouseViewportBounds = bounds;
                }
                return InteractionResult.handled();
            }
            if (pointer.kind() != PointerEvent.Kind.PRESS
                || pointer.button() != PointerEvent.Button.LEFT) {
                return InteractionResult.ignored();
            }
            Optional<Point> local = context.localPoint(pointer).filter(this::hit);
            if (local.isEmpty()) {
                return InteractionResult.ignored();
            }
            int localColumn = local.orElseThrow().x() - bounds.x();
            long targetVisual = (long) viewportLeft + localColumn;
            owner.moveToVisualColumn((int) Math.min(Integer.MAX_VALUE, targetVisual),
                pointer.modifiers().contains(KeyStroke.Modifier.SHIFT));
            owner.mouseSelecting = true;
            owner.mouseViewportBounds = bounds;
            owner.mouseViewportLeft = viewportLeft;
            return FOCUS_SELF_RESULT;
        }

        public boolean captures(PointerEvent pointer) {
            return owner.captures(pointer);
        }

        public void cancelMouseSelection() {
            owner.cancelMouseSelection();
        }

        private static InteractionResult<Action> editResult(boolean changed) {
            return changed ? VALUE_CHANGED_RESULT : InteractionResult.handled();
        }
    }
}
