package dev.tuiframe.widget;

import dev.tuiframe.cell.Canvas;
import dev.tuiframe.cell.Cell;
import dev.tuiframe.cell.CellText;
import dev.tuiframe.geometry.Point;
import dev.tuiframe.geometry.Rect;
import dev.tuiframe.geometry.Size;
import dev.tuiframe.input.KeyStroke;
import dev.tuiframe.input.PointerEvent;
import dev.tuiframe.input.ResizeEvent;
import dev.tuiframe.input.TextInput;
import dev.tuiframe.input.TuiInputEvent;
import dev.tuiframe.scroll.ScrollInteraction;
import dev.tuiframe.scroll.ScrollLayout;
import dev.tuiframe.scroll.ScrollStyle;
import dev.tuiframe.scroll.Scrollbar;
import dev.tuiframe.style.CellStyle;
import dev.tuiframe.text.TerminalText;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Immediate-mode descriptor-driven menu bar with one application-owned popup state.
 *
 * <p>Labels are presentation text. The widget inserts one blank cell between
 * top-level labels and reveals the active heading when they do not all fit.
 * Command registries, shortcuts, checked-marker
 * formatting, application models, and modal capture policy remain consumer-owned.
 * Layout returns an immutable exact-frame snapshot used by paint, hit testing,
 * input, and optional cursor geometry.</p>
 *
 * @param <C> opaque consumer-owned activation value
 */
public final class MenuBar<C> implements InteractiveWidget<MenuBar.Action<C>> {
    private static final int TAB_WIDTH = 4;
    private static final int TOP_GAP = 1;
    private static final int POPUP_CHROME_WIDTH = 4;
    private static final int MIN_POPUP_WIDTH = 6;

    private final List<Menu<C>> menus;
    private final State state;

    public MenuBar(List<Menu<C>> menus, State state) {
        this.menus = List.copyOf(Objects.requireNonNull(menus, "menus"));
        this.state = Objects.requireNonNull(state, "state");
    }

    @Override
    public Size measure(Size available) {
        return Objects.requireNonNull(available, "available");
    }

    /** Width of all complete headings and the gaps between them, before clipping. */
    public int preferredBarWidth() {
        long width = Math.max(0, menus.size() - 1) * (long) TOP_GAP;
        for (Menu<C> menu : menus) {
            width += TerminalText.metrics(menu.label(), TAB_WIDTH).visualWidth();
        }
        return (int) Math.min(Integer.MAX_VALUE, width);
    }

    @Override
    public Placement<C> layout(Rect bounds) {
        return layout(bounds, 0);
    }

    /**
     * Reserves the trailing cells of the heading row for consumer-owned chrome.
     * Popups keep the full layer bounds, including the columns below that chrome.
     */
    public Placement<C> layout(Rect bounds, int reservedTrailingColumns) {
        Rect required = Objects.requireNonNull(bounds, "bounds");
        if (reservedTrailingColumns < 0) {
            throw new IllegalArgumentException("reservedTrailingColumns must not be negative");
        }
        Rect barBounds = new Rect(required.x(), required.y(),
            required.width() - Math.min(required.width(), reservedTrailingColumns), required.height());
        State base = normalizeMenu(state, menus);
        List<PreparedTop> top = prepareTop(barBounds, base);
        PreparedTop activeTop = base.isOpen()
            ? findTop(top, base.openMenuIndex())
            : null;
        int rowCapacity = popupRowCapacity(required, base.isOpen());
        State normalized = normalizeViewport(base, menus, rowCapacity);
        if (base.selectedEntryIndex() != state.selectedEntryIndex()) {
            normalized = revealSelection(normalized, menus, rowCapacity);
        }
        Popup<C> popup = preparePopup(required, activeTop, normalized, rowCapacity);
        if (normalized.drag() != null && (popup == null || popup.scrollbar() == null)) {
            normalized = new State(normalized.openMenuIndex(), normalized.selectedEntryIndex(),
                normalized.firstVisibleEntryIndex());
        }
        return new Placement<>(required, menus, normalized, top, popup, rowCapacity);
    }

    private List<PreparedTop> prepareTop(Rect bounds, State normalized) {
        if (bounds.width() <= 0 || bounds.height() <= 0 || menus.isEmpty()) {
            return List.of();
        }
        int first = 0;
        if (normalized.isOpen()) {
            int active = normalized.openMenuIndex();
            long requiredWidth = boundedVisualWidth(menus.get(active).label(), bounds.width());
            first = active;
            while (first > 0) {
                String previous = menus.get(first - 1).label();
                PreparedLabel label = prepareCompleteLabel(previous, -1, bounds.width());
                if (label == null || requiredWidth + TOP_GAP + label.width() > bounds.width()) {
                    break;
                }
                requiredWidth += TOP_GAP + label.width();
                first--;
            }
        }
        List<PreparedTop> prepared = new ArrayList<>();
        int x = bounds.x();
        for (int index = first; index < menus.size(); index++) {
            int remaining = bounds.rightExclusive() - x;
            if (remaining <= 0) {
                break;
            }
            Menu<C> menu = menus.get(index);
            PreparedLabel label = prepareCompleteLabel(
                menu.label(),
                menu.mnemonicGraphemeIndex(),
                remaining
            );
            if (label == null && normalized.isOpen() && index == normalized.openMenuIndex()) {
                label = prepareVisibleLabel(menu.label(), menu.mnemonicGraphemeIndex(), remaining);
                if (label.width() == 0) {
                    label = prepareLabel("\u2026", -1);
                }
            }
            if (label == null || label.width() <= 0) {
                break;
            }
            Rect labelBounds = new Rect(x, bounds.y(), label.width(), 1);
            prepared.add(new PreparedTop(index, labelBounds, label));
            long next = (long) labelBounds.rightExclusive() + TOP_GAP;
            if (next > bounds.rightExclusive() || next > Integer.MAX_VALUE) {
                break;
            }
            x = (int) next;
        }
        return List.copyOf(prepared);
    }

    private Popup<C> preparePopup(
        Rect bounds,
        PreparedTop activeTop,
        State normalized,
        int rowCapacity
    ) {
        if (!normalized.isOpen() || rowCapacity <= 0
            || bounds.width() < MIN_POPUP_WIDTH) {
            return null;
        }
        List<Entry<C>> entries = menus.get(normalized.openMenuIndex()).entries();
        if (entries.isEmpty()) {
            return null;
        }
        int width = popupWidth(entries, bounds.width(), entries.size() > rowCapacity && rowCapacity >= 2);
        if (width < MIN_POPUP_WIDTH) {
            return null;
        }
        int first = normalized.firstVisibleEntryIndex();
        int count = Math.min(rowCapacity, entries.size() - first);
        if (count <= 0) {
            return null;
        }
        int anchorX = activeTop == null ? bounds.x() : activeTop.bounds().x();
        int x = Math.min(anchorX, bounds.rightExclusive() - width);
        x = Math.max(bounds.x(), x);
        int y = bounds.y() + 1;
        Rect popupBounds = new Rect(x, y, width, count + 2);
        ScrollLayout geometry = ScrollLayout.vertical(new Rect(x + 1, y + 1, width - 2, count), entries.size());
        int contentWidth = Math.max(0, geometry.contentBounds().width() - 2);
        List<PreparedRow<C>> rows = new ArrayList<>(count);
        for (int offset = 0; offset < count; offset++) {
            int entryIndex = first + offset;
            Entry<C> entry = entries.get(entryIndex);
            int rowY = y + 1 + offset;
            if (entry instanceof Item<C> item) {
                PreparedLabel label = prepareVisibleLabel(
                    item.label(),
                    item.mnemonicGraphemeIndex(),
                    contentWidth,
                    item.labelPolicy()
                );
                PreparedLabel hint = prepareVisibleLabel(item.hint(), -1, contentWidth);
                // Hints must never hide the action or make a previously usable row inactive.
                if (hint.width() == 0 || !hint.text().equals(item.hint())
                    || label.width() + 2 + hint.width() > contentWidth) {
                    hint = null;
                }
                rows.add(new PreparedRow<>(entryIndex, rowY, item, label, hint));
            } else {
                rows.add(new PreparedRow<>(entryIndex, rowY, entry, null, null));
            }
        }
        Scrollbar scrollbar = geometry.verticalScrollbarBounds() == null ? null
            : Scrollbar.vertical(geometry.verticalScrollbarBounds(), entries.size(), first);
        return new Popup<>(popupBounds, geometry.contentBounds(), List.copyOf(rows), scrollbar);
    }

    private static int popupWidth(List<? extends Entry<?>> entries, int availableWidth, boolean scrolling) {
        int maximum = Math.min(availableWidth, MIN_POPUP_WIDTH);
        int chrome = POPUP_CHROME_WIDTH + (scrolling ? 1 : 0);
        int labelLimit = Math.max(0, availableWidth - chrome);
        for (Entry<?> entry : entries) {
            if (!(entry instanceof Item<?> item)) {
                continue;
            }
            int labelWidth = boundedVisualWidth(item.label(), labelLimit);
            int hintWidth = boundedVisualWidth(item.hint(), labelLimit);
            long preferred = (long) labelWidth + (hintWidth == 0 ? 0 : hintWidth + 2) + chrome;
            maximum = Math.max(maximum, (int) Math.min(availableWidth, preferred));
            if (maximum == availableWidth) {
                break;
            }
        }
        return maximum;
    }

    private static int popupRowCapacity(Rect bounds, boolean open) {
        if (!open || bounds.height() < 4 || bounds.width() < MIN_POPUP_WIDTH) {
            return 0;
        }
        return bounds.height() - 3;
    }

    private static State normalizeMenu(State requested, List<? extends Menu<?>> menus) {
        if (!requested.isOpen() || requested.openMenuIndex() >= menus.size()) {
            return State.closed();
        }
        List<? extends Entry<?>> entries = menus.get(requested.openMenuIndex()).entries();
        int selected = requested.selectedEntryIndex();
        if (!selectable(entries, selected)) {
            selected = firstSelectable(entries);
        }
        return new State(requested.openMenuIndex(), selected, requested.firstVisibleEntryIndex(), requested.drag());
    }

    private static State normalizeViewport(
        State requested,
        List<? extends Menu<?>> menus,
        int capacity
    ) {
        if (!requested.isOpen()) {
            return State.closed();
        }
        int count = menus.get(requested.openMenuIndex()).entries().size();
        int first = Math.min(requested.firstVisibleEntryIndex(), Math.max(0, count - 1));
        if (capacity > 0) {
            int maxFirst = Math.max(0, count - capacity);
            first = Math.min(first, maxFirst);
        }
        return new State(requested.openMenuIndex(), requested.selectedEntryIndex(), first, requested.drag());
    }

    private static State revealSelection(State requested, List<? extends Menu<?>> menus, int capacity) {
        State normalized = normalizeViewport(requested, menus, capacity);
        int first = normalized.firstVisibleEntryIndex();
        int selected = normalized.selectedEntryIndex();
        if (capacity > 0 && selected >= 0) {
            if (selected < first) {
                first = selected;
            } else if (selected >= first + capacity) {
                first = selected - capacity + 1;
            }
        }
        return new State(normalized.openMenuIndex(), selected, first);
    }

    private static State openState(int menuIndex, List<? extends Menu<?>> menus, int capacity) {
        List<? extends Entry<?>> entries = menus.get(menuIndex).entries();
        State state = new State(menuIndex, firstSelectable(entries), 0);
        return revealSelection(state, menus, capacity);
    }

    private static boolean selectable(List<? extends Entry<?>> entries, int index) {
        return index >= 0 && index < entries.size()
            && entries.get(index) instanceof Item<?> item
            && item.enabled();
    }

    private static int firstSelectable(List<? extends Entry<?>> entries) {
        for (int index = 0; index < entries.size(); index++) {
            if (selectable(entries, index)) {
                return index;
            }
        }
        return -1;
    }

    private static int lastSelectable(List<? extends Entry<?>> entries) {
        for (int index = entries.size() - 1; index >= 0; index--) {
            if (selectable(entries, index)) {
                return index;
            }
        }
        return -1;
    }

    private static int moveSelection(List<? extends Entry<?>> entries, int selected, int delta) {
        if (entries.isEmpty()) {
            return -1;
        }
        int selectableCount = 0;
        for (Entry<?> entry : entries) {
            if (entry instanceof Item<?> item && item.enabled()) {
                selectableCount++;
            }
        }
        if (selectableCount == 0) {
            return -1;
        }
        int next = selected;
        if (next < 0 || next >= entries.size()) {
            return delta < 0 ? lastSelectable(entries) : firstSelectable(entries);
        }
        for (int probe = 0; probe < entries.size(); probe++) {
            next = Math.floorMod(next + delta, entries.size());
            if (selectable(entries, next)) {
                return next;
            }
        }
        return selected;
    }

    private static PreparedLabel prepareCompleteLabel(String text, int mnemonic, int capacity) {
        if (capacity <= 0) {
            return null;
        }
        String prefix = TerminalText.prefixForVisualWidth(text, capacity, TAB_WIDTH);
        if (prefix.length() != text.length()) {
            return null;
        }
        PreparedLabel prepared = prepareLabel(prefix, mnemonic);
        return prepared.width() <= capacity ? prepared : null;
    }

    private static PreparedLabel prepareVisibleLabel(String text, int mnemonic, int capacity) {
        return prepareVisibleLabel(text, mnemonic, capacity, LabelPolicy.DEFAULT);
    }

    private static PreparedLabel prepareVisibleLabel(String text, int mnemonic, int capacity, LabelPolicy labelPolicy) {
        if (capacity <= 0) {
            return new PreparedLabel("", 0, -1, null);
        }
        String prefix = labelPolicy == LabelPolicy.DEFAULT
            ? TerminalText.prefixForVisualWidth(text, capacity, TAB_WIDTH)
            : labelPolicy.clip(text, 0, capacity);
        TerminalText.Metrics metrics = TerminalText.metrics(prefix, TAB_WIDTH);
        int end = 0;
        for (int index = 0; index < metrics.graphemeCount(); index++) {
            if (metrics.visualColumnAtBoundary(index + 1) > capacity) {
                break;
            }
            end = metrics.end(index);
        }
        return prepareLabel(prefix.substring(0, end), mnemonic);
    }

    private static PreparedLabel prepareLabel(String text, int mnemonic) {
        TerminalText.Metrics metrics = TerminalText.metrics(text, TAB_WIDTH);
        int visual = -1;
        String mnemonicText = null;
        if (mnemonic >= 0 && mnemonic < metrics.graphemeCount() && !metrics.isTab(mnemonic)) {
            visual = metrics.visualColumnAtBoundary(mnemonic);
            mnemonicText = text.substring(metrics.start(mnemonic), metrics.end(mnemonic));
        }
        return new PreparedLabel(text, metrics.visualWidth(), visual, mnemonicText);
    }

    private static int boundedVisualWidth(String text, int limit) {
        if (limit <= 0) {
            return 0;
        }
        String prefix = TerminalText.prefixForVisualWidth(text, limit, TAB_WIDTH);
        return Math.min(limit, TerminalText.metrics(prefix, TAB_WIDTH).visualWidth());
    }

    private static PreparedTop findTop(List<PreparedTop> tops, int menuIndex) {
        for (PreparedTop top : tops) {
            if (top.menuIndex() == menuIndex) {
                return top;
            }
        }
        return null;
    }

    /** One top-level menu descriptor. Its label is painted verbatim. */
    public record Menu<C>(
        String label,
        int mnemonicGraphemeIndex,
        List<Entry<C>> entries
    ) {
        public Menu {
            requireLabel(label, "label");
            if (mnemonicGraphemeIndex < -1) {
                throw new IllegalArgumentException("mnemonicGraphemeIndex must be -1 or greater");
            }
            entries = List.copyOf(Objects.requireNonNull(entries, "entries"));
        }
    }

    /** Popup row descriptor. */
    public sealed interface Entry<C> permits Item, Separator {
    }

    /**
     * Enabled or disabled popup activation row with an optional consumer-owned trailing hint.
     * Hints align at the right edge when they fit and are omitted before action labels are clipped.
     * Their meaning and keyboard bindings remain the consumer's responsibility.
     */
    public record Item<C>(
        C value,
        String label,
        int mnemonicGraphemeIndex,
        boolean enabled,
        LabelPolicy labelPolicy,
        String hint
    ) implements Entry<C> {
        public Item(C value, String label, int mnemonicGraphemeIndex, boolean enabled) {
            this(value, label, mnemonicGraphemeIndex, enabled, LabelPolicy.DEFAULT, "");
        }

        public Item(C value, String label, int mnemonicGraphemeIndex, boolean enabled, LabelPolicy labelPolicy) {
            this(value, label, mnemonicGraphemeIndex, enabled, labelPolicy, "");
        }

        public Item {
            Objects.requireNonNull(value, "value");
            requireLabel(label, "label");
            Objects.requireNonNull(labelPolicy, "labelPolicy");
            Objects.requireNonNull(hint, "hint");
            if (mnemonicGraphemeIndex < -1) {
                throw new IllegalArgumentException("mnemonicGraphemeIndex must be -1 or greater");
            }
        }
    }

    /** Non-selectable popup separator row. */
    public record Separator<C>() implements Entry<C> {
    }

    /** Application-owned open menu, selection, vertical viewport, and scrollbar capture. */
    public record State(
        int openMenuIndex,
        int selectedEntryIndex,
        int firstVisibleEntryIndex,
        Scrollbar.Drag drag
    ) {
        public State {
            if (openMenuIndex < -1) {
                throw new IllegalArgumentException("openMenuIndex must be -1 or greater");
            }
            if (selectedEntryIndex < -1) {
                throw new IllegalArgumentException("selectedEntryIndex must be -1 or greater");
            }
            if (firstVisibleEntryIndex < 0) {
                throw new IllegalArgumentException("firstVisibleEntryIndex must not be negative");
            }
            if (drag != null && drag.axis() != Scrollbar.Axis.VERTICAL) {
                throw new IllegalArgumentException("a menu can only capture its vertical scrollbar");
            }
            if (openMenuIndex == -1 && (selectedEntryIndex != -1 || firstVisibleEntryIndex != 0 || drag != null)) {
                throw new IllegalArgumentException("closed state must not retain selection or viewport");
            }
        }

        public State(int openMenuIndex, int selectedEntryIndex, int firstVisibleEntryIndex) {
            this(openMenuIndex, selectedEntryIndex, firstVisibleEntryIndex, null);
        }

        public static State closed() {
            return new State(-1, -1, 0);
        }

        public static State open(int menuIndex) {
            if (menuIndex < 0) {
                throw new IllegalArgumentException("menuIndex must not be negative");
            }
            return new State(menuIndex, -1, 0);
        }

        public boolean isOpen() {
            return openMenuIndex >= 0;
        }

        public State cancelPointerInteraction() {
            return drag == null ? this : new State(openMenuIndex, selectedEntryIndex, firstVisibleEntryIndex);
        }
    }

    /** Semantic menu action independent of an application command model. */
    public sealed interface Action<C> permits StateChanged, Invoked, Unavailable {
    }

    /** Opening, dismissal, menu switching, or navigation produced a new state. */
    public record StateChanged<C>(State state) implements Action<C> {
        public StateChanged {
            Objects.requireNonNull(state, "state");
        }
    }

    /** A visible enabled item was invoked; {@code state} is always closed. */
    public record Invoked<C>(C value, State state) implements Action<C> {
        public Invoked {
            Objects.requireNonNull(value, "value");
            Objects.requireNonNull(state, "state");
            if (state.isOpen()) {
                throw new IllegalArgumentException("invocation state must be closed");
            }
        }
    }

    /** A readable disabled item's activation was attempted; the menu remains open and unchanged. */
    public record Unavailable<C>(C value, State state) implements Action<C> {
        public Unavailable {
            Objects.requireNonNull(value, "value");
            Objects.requireNonNull(state, "state");
            if (!state.isOpen()) throw new IllegalArgumentException("unavailable state must remain open");
        }
    }

    /**
     * Immutable exact-frame placement. Rebuild after descriptors, state, or
     * bounds change; an older placement intentionally keeps its old snapshot.
     */
    public static final class Placement<C>
        implements PlacedPointerCapture<Action<C>>, PlacedCursorSource {
        private final Rect bounds;
        private final List<Menu<C>> menus;
        private final State state;
        private final List<PreparedTop> top;
        private final Popup<C> popup;
        private final int rowCapacity;

        private Placement(
            Rect bounds,
            List<Menu<C>> menus,
            State state,
            List<PreparedTop> top,
            Popup<C> popup,
            int rowCapacity
        ) {
            this.bounds = bounds;
            this.menus = menus;
            this.state = state;
            this.top = top;
            this.popup = popup;
            this.rowCapacity = rowCapacity;
        }

        @Override
        public Rect bounds() {
            return bounds;
        }

        public State state() {
            return state;
        }

        public Optional<Rect> popupBounds() {
            return popup == null ? Optional.empty() : Optional.of(popup.bounds());
        }

        public Optional<Scrollbar> scrollbar() {
            return popup == null ? Optional.empty() : Optional.ofNullable(popup.scrollbar());
        }

        @Override
        public boolean hit(int row, int column) {
            if (state.isOpen()) {
                return bounds.contains(column, row);
            }
            for (PreparedTop item : top) {
                if (item.bounds().contains(column, row)) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public boolean hit(Point point) {
            Objects.requireNonNull(point, "point");
            return hit(point.y(), point.x());
        }

        @Override
        public void paint(Canvas canvas, Theme theme, WidgetState widgetState) {
            Objects.requireNonNull(canvas, "canvas");
            Objects.requireNonNull(theme, "theme");
            Objects.requireNonNull(widgetState, "widgetState");
            if (bounds.isEmpty()) {
                return;
            }
            CellStyle bar = theme.resolveStyle(WidgetThemeKeys.MENU_BAR);
            CellStyle disabledBar = theme.resolveStyle(WidgetThemeKeys.MENU_BAR_DISABLED, bar);
            CellStyle activeBar = theme.resolveStyle(WidgetThemeKeys.MENU_BAR_ACTIVE, bar);
            CellStyle ordinaryTop = widgetState.enabled() ? bar : disabledBar;
            CellStyle ordinaryMnemonic = underlined(ordinaryTop);
            CellStyle activeMnemonic = widgetState.enabled() && state.isOpen()
                ? underlined(activeBar)
                : ordinaryMnemonic;
            int mark = canvas.save();
            try {
                if (!canvas.clip(bounds)) {
                    return;
                }
                if (!top.isEmpty()) {
                    int right = top.getLast().bounds().rightExclusive();
                    canvas.fill(bounds.x(), bounds.y(), right - bounds.x(), 1, ordinaryTop);
                }
                for (PreparedTop item : top) {
                    CellStyle style = !widgetState.enabled()
                        ? disabledBar
                        : state.isOpen() && item.menuIndex() == state.openMenuIndex()
                            ? activeBar
                            : bar;
                    CellStyle mnemonicStyle = widgetState.enabled() && state.isOpen()
                        && item.menuIndex() == state.openMenuIndex()
                            ? activeMnemonic
                            : ordinaryMnemonic;
                    paintLabel(
                        canvas,
                        item.bounds().x(),
                        item.bounds().y(),
                        item.label(),
                        style,
                        mnemonicStyle
                    );
                }
                if (popup != null) {
                    paintPopup(canvas, theme, widgetState);
                }
            } finally {
                canvas.restore(mark);
            }
        }

        private void paintPopup(Canvas canvas, Theme theme, WidgetState widgetState) {
            CellStyle popupStyle = theme.resolveStyle(WidgetThemeKeys.MENU_POPUP);
            CellStyle itemStyle = theme.resolveStyle(WidgetThemeKeys.MENU_ITEM, popupStyle);
            CellStyle selectedStyle = theme.resolveStyle(
                WidgetThemeKeys.MENU_ITEM_SELECTED,
                itemStyle
            );
            CellStyle disabledStyle = theme.resolveStyle(
                WidgetThemeKeys.MENU_ITEM_DISABLED,
                itemStyle
            );
            CellStyle itemMnemonic = underlined(itemStyle);
            CellStyle selectedMnemonic = underlined(selectedStyle);
            CellStyle disabledMnemonic = underlined(disabledStyle);
            Rect box = popup.bounds();
            Cell horizontal = new Cell("─", popupStyle);
            Cell vertical = new Cell("│", popupStyle);
            canvas.put(box.x(), box.y(), "┌", popupStyle);
            canvas.fillCell(box.x() + 1, box.y(), box.width() - 2, 1, horizontal);
            canvas.put(box.rightExclusive() - 1, box.y(), "┐", popupStyle);
            for (PreparedRow<C> row : popup.rows()) {
                canvas.put(box.x(), row.y(), vertical);
                canvas.put(box.rightExclusive() - 1, row.y(), vertical);
                if (row.entry() instanceof Separator<?>) {
                    canvas.fillCell(box.x() + 1, row.y(), popup.contentBounds().width(), 1, horizontal);
                    continue;
                }
                CellStyle rowStyle = !widgetState.enabled() || !row.activatable()
                    ? disabledStyle
                    : row.entryIndex() == state.selectedEntryIndex()
                        ? selectedStyle
                        : itemStyle;
                CellStyle mnemonicStyle = !widgetState.enabled() || !row.activatable()
                    ? disabledMnemonic
                    : row.entryIndex() == state.selectedEntryIndex()
                        ? selectedMnemonic
                        : itemMnemonic;
                canvas.fill(box.x() + 1, row.y(), popup.contentBounds().width(), 1, rowStyle);
                paintLabel(
                    canvas,
                    box.x() + 2,
                    row.y(),
                    row.label(),
                    rowStyle,
                    mnemonicStyle
                );
                if (row.hint() != null) {
                    paintLabel(canvas, popup.contentBounds().rightExclusive() - 1 - row.hint().width(),
                        row.y(), row.hint(), rowStyle, rowStyle);
                }
            }
            int bottom = box.bottomExclusive() - 1;
            canvas.put(box.x(), bottom, "└", popupStyle);
            canvas.fillCell(box.x() + 1, bottom, box.width() - 2, 1, horizontal);
            canvas.put(box.rightExclusive() - 1, bottom, "┘", popupStyle);
            scrollbar().ifPresent(bar -> bar.draw(canvas, ScrollStyle.fromParent(itemStyle, popupStyle)));
        }

        private static void paintLabel(
            Canvas canvas,
            int x,
            int y,
            PreparedLabel label,
            CellStyle style,
            CellStyle mnemonicStyle
        ) {
            if (label.width() <= 0) {
                return;
            }
            CellText.drawClipped(canvas, x, y, label.text(), style, label.width());
            if (label.mnemonicVisual() >= 0 && label.mnemonicText() != null) {
                canvas.put(
                    x + label.mnemonicVisual(),
                    y,
                    label.mnemonicText(),
                    mnemonicStyle
                );
            }
        }

        @Override
        public boolean pointerCaptureActive() {
            return state.drag() != null;
        }

        @Override
        public InteractionResult<Action<C>> cancelPointerCapture(PointerCaptureCancellation reason) {
            Objects.requireNonNull(reason, "reason");
            return (state.drag() == null ? InteractionResult.<Action<C>>handled()
                : InteractionResult.<Action<C>>output(new StateChanged<>(state.cancelPointerInteraction())))
                .withPointerCapture(PointerCaptureRequest.RELEASE);
        }

        @Override
        public InteractionResult<Action<C>> handle(TuiInputEvent event, FocusContext context) {
            Objects.requireNonNull(event, "event");
            Objects.requireNonNull(context, "context");
            if (state.drag() != null && (event instanceof ResizeEvent
                || event instanceof PointerEvent pointer && pointer.kind() == PointerEvent.Kind.RELEASE)) {
                return cancelPointerCapture(event instanceof ResizeEvent
                    ? PointerCaptureCancellation.RESIZE : PointerCaptureCancellation.RELEASE);
            }
            if (!context.enabled() || bounds.isEmpty() || menus.isEmpty()) {
                return InteractionResult.ignored();
            }
            if (event instanceof PointerEvent pointer) {
                return handlePointer(pointer, context);
            }
            if (!state.isOpen() || !context.focused()) {
                return InteractionResult.ignored();
            }
            if (event instanceof KeyStroke stroke && stroke.modifiers().isEmpty()) {
                InteractionResult<Action<C>> result = handleKey(stroke.key());
                return result == null ? InteractionResult.handled() : result;
            }
            if (event instanceof TextInput input) {
                InteractionResult<Action<C>> result = handleMnemonic(input.text());
                return result == null ? InteractionResult.handled() : result;
            }
            return InteractionResult.handled();
        }

        private InteractionResult<Action<C>> handlePointer(
            PointerEvent pointer,
            FocusContext context
        ) {
            if (Scrollbar.captures(pointer, state.drag())) {
                Point point = new Point(
                    signedInt((long) pointer.column() - context.space().terminalOrigin().x()),
                    signedInt((long) pointer.row() - context.space().terminalOrigin().y())
                );
                int first = scrollbar().orElseThrow().drag(point, state.drag());
                return InteractionResult.output(new StateChanged<>(new State(
                    state.openMenuIndex(), state.selectedEntryIndex(), first, state.drag()
                )));
            }
            Optional<Point> local = context.localPoint(pointer).filter(bounds::contains);
            if (local.isEmpty()) {
                return InteractionResult.ignored();
            }
            Point point = local.orElseThrow();
            if (pointer.modifiers().isEmpty() && popup != null && popup.bounds().contains(point)
                && (pointer.kind() == PointerEvent.Kind.SCROLL_UP
                    || pointer.kind() == PointerEvent.Kind.SCROLL_DOWN)) {
                int delta = pointer.kind() == PointerEvent.Kind.SCROLL_UP ? -3 : 3;
                int first = ScrollInteraction.scrollBy(state.firstVisibleEntryIndex(), delta, rowCapacity,
                    menus.get(state.openMenuIndex()).entries().size());
                State next = new State(state.openMenuIndex(), state.selectedEntryIndex(), first);
                return next.equals(state) ? InteractionResult.handled()
                    : InteractionResult.output(new StateChanged<>(next));
            }
            if (!pointer.modifiers().isEmpty()
                || pointer.kind() != PointerEvent.Kind.PRESS
                || pointer.button() != PointerEvent.Button.LEFT) {
                return state.isOpen() ? InteractionResult.handled() : InteractionResult.ignored();
            }
            PreparedTop topHit = topAt(point);
            if (topHit != null) {
                State next = state.isOpen() && state.openMenuIndex() == topHit.menuIndex()
                    ? State.closed()
                    : openState(topHit.menuIndex(), menus, rowCapacity);
                return InteractionResult.output(new StateChanged<>(next));
            }
            if (!state.isOpen()) {
                return InteractionResult.ignored();
            }
            Optional<Scrollbar.Press> press = scrollbar().flatMap(bar -> bar.press(point));
            if (press.isPresent()) {
                Scrollbar.Press captured = press.orElseThrow();
                return InteractionResult.<Action<C>>output(new StateChanged<>(new State(
                    state.openMenuIndex(), state.selectedEntryIndex(), captured.offset(), captured.drag()
                ))).withPointerCapture(captured.drag() == null
                    ? PointerCaptureRequest.NONE : PointerCaptureRequest.ACQUIRE);
            }
            PreparedRow<C> row = popup == null ? null : popup.rowAt(point);
            if (row != null && row.entry() instanceof Item<?> raw) {
                @SuppressWarnings("unchecked")
                Item<C> item = (Item<C>) raw;
                return activate(row, item);
            }
            if (popup != null && popup.bounds().contains(point)) {
                return InteractionResult.handled();
            }
            return InteractionResult.output(new StateChanged<>(State.closed()));
        }

        private InteractionResult<Action<C>> handleKey(KeyStroke.Key key) {
            return switch (key) {
                case ESCAPE -> InteractionResult.output(new StateChanged<>(State.closed()));
                case ARROW_LEFT -> switchMenu(-1);
                case ARROW_RIGHT -> switchMenu(1);
                case ARROW_UP -> move(-1);
                case ARROW_DOWN -> move(1);
                case ENTER -> invokeSelected();
                default -> null;
            };
        }

        private InteractionResult<Action<C>> switchMenu(int delta) {
            int next = Math.floorMod(state.openMenuIndex() + delta, menus.size());
            return InteractionResult.output(new StateChanged<>(openState(next, menus, rowCapacity)));
        }

        private InteractionResult<Action<C>> move(int delta) {
            List<Entry<C>> entries = menus.get(state.openMenuIndex()).entries();
            int selected = moveSelection(entries, state.selectedEntryIndex(), delta);
            State next = revealSelection(
                new State(state.openMenuIndex(), selected, state.firstVisibleEntryIndex()),
                menus,
                rowCapacity
            );
            return next.equals(state)
                ? InteractionResult.handled()
                : InteractionResult.output(new StateChanged<>(next));
        }

        private InteractionResult<Action<C>> invokeSelected() {
            PreparedRow<C> row = popup == null
                ? null
                : popup.rowForEntry(state.selectedEntryIndex());
            if (row == null && rowCapacity > 0 && state.selectedEntryIndex() >= 0) {
                State next = revealSelection(state, menus, rowCapacity);
                return next.equals(state) ? InteractionResult.handled()
                    : InteractionResult.output(new StateChanged<>(next));
            }
            if (row == null || !(row.entry() instanceof Item<?> raw)) {
                return InteractionResult.handled();
            }
            @SuppressWarnings("unchecked")
            Item<C> item = (Item<C>) raw;
            return activate(row, item);
        }

        private InteractionResult<Action<C>> handleMnemonic(String text) {
            TerminalText.Metrics input = TerminalText.metrics(text, TAB_WIDTH);
            if (input.graphemeCount() != 1) {
                return null;
            }
            String grapheme = text.substring(input.start(0), input.end(0));
            if (grapheme.length() == 1 && grapheme.charAt(0) >= '1'
                && grapheme.charAt(0) <= '9') {
                return handleOrdinal(grapheme.charAt(0) - '0');
            }
            if (popup == null) {
                return null;
            }
            for (PreparedRow<C> row : popup.rows()) {
                if (!(row.entry() instanceof Item<?> raw) || row.label() == null
                    || row.label().mnemonicText() == null) {
                    continue;
                }
                @SuppressWarnings("unchecked")
                Item<C> item = (Item<C>) raw;
                if (row.readable() && mnemonicEquals(row.label().mnemonicText(), grapheme)) {
                    return activate(row, item);
                }
            }
            return null;
        }

        private InteractionResult<Action<C>> handleOrdinal(int ordinal) {
            List<Entry<C>> entries = menus.get(state.openMenuIndex()).entries();
            int remaining = ordinal;
            int target = -1;
            for (int index = 0; index < entries.size(); index++) {
                if (selectable(entries, index) && --remaining == 0) {
                    target = index;
                    break;
                }
            }
            if (target < 0) {
                return null;
            }
            PreparedRow<C> visible = popup == null ? null : popup.rowForEntry(target);
            if (visible != null && visible.entry() instanceof Item<?> raw) {
                @SuppressWarnings("unchecked")
                Item<C> item = (Item<C>) raw;
                return activate(visible, item);
            }
            if (rowCapacity <= 0) {
                return InteractionResult.handled();
            }
            State next = revealSelection(
                new State(state.openMenuIndex(), target, state.firstVisibleEntryIndex()),
                menus,
                rowCapacity
            );
            return next.equals(state)
                ? InteractionResult.handled()
                : InteractionResult.output(new StateChanged<>(next));
        }

        private InteractionResult<Action<C>> activate(PreparedRow<C> row, Item<C> item) {
            if (!row.readable()) return InteractionResult.handled();
            return item.enabled() ? invoke(item.value())
                : InteractionResult.output(new Unavailable<>(item.value(), state));
        }

        private InteractionResult<Action<C>> invoke(C value) {
            return InteractionResult.output(new Invoked<>(value, State.closed()));
        }

        private PreparedTop topAt(Point point) {
            for (PreparedTop item : top) {
                if (item.bounds().contains(point)) {
                    return item;
                }
            }
            return null;
        }

        @Override
        public Optional<Point> cursorPosition(FocusContext context) {
            Objects.requireNonNull(context, "context");
            if (!context.focused() || !context.enabled() || popup == null) {
                return Optional.empty();
            }
            PreparedRow<C> row = popup.rowForEntry(state.selectedEntryIndex());
            if (row == null || !row.activatable()) {
                return Optional.empty();
            }
            int offset = row.label() != null && row.label().mnemonicVisual() >= 0
                ? row.label().mnemonicVisual()
                : 0;
            Point point = new Point(popup.bounds().x() + 2 + offset, row.y());
            return popup.bounds().contains(point) && context.space().parentClip().contains(point)
                ? Optional.of(point)
                : Optional.empty();
        }
    }

    private static void requireLabel(String label, String name) {
        Objects.requireNonNull(label, name);
        if (label.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be empty");
        }
    }

    private static CellStyle underlined(CellStyle style) {
        return style.underline()
            ? style
            : style.emphasis(style.bold(), true);
    }

    private static boolean mnemonicEquals(String expected, String input) {
        return Normalizer.normalize(expected, Normalizer.Form.NFC).equalsIgnoreCase(
            Normalizer.normalize(input, Normalizer.Form.NFC)
        );
    }

    private record PreparedLabel(
        String text,
        int width,
        int mnemonicVisual,
        String mnemonicText
    ) {
    }

    private record PreparedTop(int menuIndex, Rect bounds, PreparedLabel label) {
    }

    private record PreparedRow<C>(int entryIndex, int y, Entry<C> entry, PreparedLabel label, PreparedLabel hint) {
        private boolean activatable() {
            return entry instanceof Item<?> item && item.enabled() && readable();
        }
        private boolean readable() {
            return entry instanceof Item<?> item && label != null && label.text().equals(item.label());
        }
    }

    private static int signedInt(long value) {
        return (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, value));
    }

    private record Popup<C>(
        Rect bounds,
        Rect contentBounds,
        List<PreparedRow<C>> rows,
        Scrollbar scrollbar
    ) {
        private PreparedRow<C> rowAt(Point point) {
            if (!contentBounds.contains(point)) {
                return null;
            }
            for (PreparedRow<C> row : rows) {
                if (row.y() == point.y()) {
                    return row;
                }
            }
            return null;
        }

        private PreparedRow<C> rowForEntry(int entryIndex) {
            for (PreparedRow<C> row : rows) {
                if (row.entryIndex() == entryIndex) {
                    return row;
                }
            }
            return null;
        }
    }
}
