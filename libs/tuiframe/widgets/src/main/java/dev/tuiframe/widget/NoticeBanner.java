package dev.tuiframe.widget;

import dev.tuiframe.cell.Canvas;
import dev.tuiframe.cell.CellText;
import dev.tuiframe.geometry.Rect;
import dev.tuiframe.input.PointerEvent;
import dev.tuiframe.input.TuiInputEvent;
import dev.tuiframe.style.CellStyle;
import dev.tuiframe.text.TerminalPlainText;
import java.util.Objects;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

/** A notice with owner-defined actions. Its owner controls visibility and lifetime. */
public final class NoticeBanner {
    public enum Severity { SUCCESS, INFO, WARNING, ERROR }
    public record Action(String id, String label) {
        public static final Action DISMISS = new Action("dismiss", "Dismiss");
        public Action {
            Objects.requireNonNull(id, "id");
            label = TerminalPlainText.singleLine(Objects.requireNonNull(label, "label"));
            if (id.isBlank() || label.isBlank()) throw new IllegalArgumentException("Notice actions need an ID and label");
        }
    }

    private final String text;
    private final Severity severity;
    private final List<Action> actions;
    private final boolean wrapping;

    public NoticeBanner(String text, Severity severity) {
        this(text, severity, List.of(Action.DISMISS));
    }

    public NoticeBanner(String text, Severity severity, List<Action> actions) {
        this(text, severity, actions, false);
    }

    private NoticeBanner(String text, Severity severity, List<Action> actions, boolean wrapping) {
        this.text = TerminalPlainText.singleLine(Objects.requireNonNull(text, "text"));
        this.severity = Objects.requireNonNull(severity, "severity");
        this.actions = List.copyOf(actions);
        this.wrapping = wrapping;
        var ids = new HashSet<String>();
        for (var action : this.actions)
            if (!ids.add(action.id())) throw new IllegalArgumentException("Notice action IDs must be unique");
    }

    public String text() { return text; }
    public Severity severity() { return severity; }
    public List<Action> actions() { return actions; }
    /** Plain notices can reserve enough rows for their complete instructions. */
    public NoticeBanner wrapped() {
        if (!actions.isEmpty()) throw new IllegalStateException("Wrapped notices have no actions");
        return new NoticeBanner(text, severity, actions, true);
    }
    private int actionsWidth() { return actions.stream().mapToInt(action -> new Button(action.label()).width()).sum() + Math.max(0, actions.size() - 1); }
    public int preferredHeight(int columns) {
        if (wrapping) return messageLines(columns).size();
        if (actions.isEmpty() || columns >= actionsWidth() + 3) return 1;
        int rows = 1, used = 0;
        for (var action : actions) {
            int width = new Button(action.label()).width();
            if (used == 0 || used + 1 + width > columns) { rows++; used = Math.min(Math.max(0, columns), width); }
            else used += 1 + width;
        }
        return rows;
    }

    private List<String> messageLines(int columns) {
        if (!wrapping || columns <= 0) return List.of(text);
        var lines = new ArrayList<String>();
        var row = new StringBuilder();
        var cursor = dev.tuiframe.text.TerminalText.graphemeCursor(text);
        int width = 0;
        while (cursor.advance()) {
            String glyph = text.substring(cursor.start(), cursor.end());
            int cells = CellText.width(glyph);
            if (cells > columns) { glyph = "�"; cells = 1; }
            if (width + cells > columns && !row.isEmpty()) {
                int space = row.lastIndexOf(" ");
                if (space > 0) {
                    lines.add(row.substring(0, space));
                    row.delete(0, space + 1);
                    width = CellText.width(row.toString());
                } else { lines.add(row.toString()); row.setLength(0); width = 0; }
            }
            if (row.isEmpty() && glyph.equals(" ")) continue;
            row.append(glyph); width += cells;
        }
        if (!row.isEmpty() || lines.isEmpty()) lines.add(row.toString());
        return List.copyOf(lines);
    }

    public Placement place(Object scopeId, Rect bounds, Rect clip, boolean enabled) {
        Objects.requireNonNull(scopeId, "scopeId");
        Objects.requireNonNull(bounds, "bounds");
        Objects.requireNonNull(clip, "clip");
        boolean separate = preferredHeight(bounds.width()) > 1;
        Rect messageBounds = new Rect(bounds.x(), bounds.y(), separate ? bounds.width()
            : Math.max(0, bounds.width() - actionsWidth() - (actions.isEmpty() ? 0 : 1)), wrapping ? bounds.height() : Math.min(1, bounds.height()));
        var targets = new ArrayList<RoutedTarget<Action, Action>>();
        int row = bounds.y() + (separate ? 1 : 0);
        int column = separate ? bounds.x() : bounds.rightExclusive() - actionsWidth();
        for (var action : actions) {
            var button = new Button(action.label());
            if (separate && column != bounds.x() && column + button.width() > bounds.rightExclusive()) { row++; column = bounds.x(); }
            var buttonBounds = new Rect(Math.max(bounds.x(), column), row,
                Math.min(bounds.width(), button.width()), row < bounds.bottomExclusive() ? 1 : 0);
            targets.add(new RoutedTarget<>(action, button.layout(buttonBounds).mapOutput(ignored -> action),
                FocusSpace.root(clip.intersection(bounds)), true, enabled));
            column += button.width() + 1;
        }
        var scope = new FocusScope<>(scopeId, FocusResetPolicy.CLEARED, targets, actions, actions);
        return new Placement(this, bounds, clip.intersection(bounds), messageBounds, scope, messageLines(messageBounds.width()));
    }

    public record Placement(NoticeBanner banner, Rect bounds, Rect clip, Rect messageBounds,
        FocusScope<Action, Action> scope, List<String> messageLines) implements PlacedInteractiveWidget<Action> {
        public Placement {
            Objects.requireNonNull(banner); Objects.requireNonNull(bounds); Objects.requireNonNull(clip);
            Objects.requireNonNull(messageBounds); Objects.requireNonNull(scope);
            messageLines = List.copyOf(messageLines);
        }

        public Rect actionBounds(Action action) { return scope.target(action).orElseThrow().placement().bounds(); }
        public Rect dismissBounds() { return actionBounds(Action.DISMISS); }

        @Override public boolean interactionReady() { return scope.targets().stream().anyMatch(RoutedTarget::interactive); }

        @Override public InteractionResult<Action> handle(TuiInputEvent event, FocusContext context) {
            Objects.requireNonNull(event); Objects.requireNonNull(context);
            var space = new FocusSpace(context.space().terminalOrigin(), context.space().parentClip().intersection(clip));
            for (var action : banner.actions()) {
                var target = scope.target(action).orElseThrow();
                boolean enabled = context.enabled() && target.interactive();
                if (!(event instanceof PointerEvent) && (!context.focused() || !target.focusEligible())) continue;
                var result = target.placement().handle(event, new FocusContext(context.focused() && enabled, enabled, space));
                if (result.consumed() || result.output().isPresent()) return result;
                if (!(event instanceof PointerEvent)) break;
            }
            return InteractionResult.ignored();
        }

        public InteractionResult<Action> handle(TuiInputEvent event, FocusManager<Action> focus) {
            var route = focus.route(scope, event);
            if (route.navigationConsumed()) return InteractionResult.handled();
            if (route.target().isEmpty()) return InteractionResult.ignored();
            var target = route.target().orElseThrow();
            var result = target.placement().handle(event, route.context().orElseThrow());
            focus.apply(scope, target.id(), result.focusRequest());
            return result;
        }

        @Override public void paint(Canvas canvas, Theme theme, WidgetState state) {
            paint(canvas, theme, state, null);
        }

        public void paint(Canvas canvas, Theme theme, FocusManager<Action> focus) {
            paint(canvas, theme, WidgetState.NORMAL, Objects.requireNonNull(focus));
        }

        private void paint(Canvas canvas, Theme theme, WidgetState state, FocusManager<Action> focus) {
            Objects.requireNonNull(canvas); Objects.requireNonNull(theme); Objects.requireNonNull(state);
            WidgetStyleRole role = switch (banner.severity()) {
                case SUCCESS -> WidgetThemeKeys.NOTICE_SUCCESS;
                case INFO -> WidgetThemeKeys.NOTICE_INFO;
                case WARNING -> WidgetThemeKeys.NOTICE_WARNING;
                case ERROR -> WidgetThemeKeys.NOTICE_ERROR;
            };
            CellStyle body = theme.resolveStyle(role, theme.resolveStyle(WidgetThemeKeys.MODAL_BODY));
            Theme buttonTheme = key -> {
                if (WidgetThemeKeys.BUTTON_FOCUSED.equals(key))
                    return new CellStyle(body.bg(), body.fg(), true, true, body.inverse());
                if (WidgetThemeKeys.BUTTON.equals(key) || WidgetThemeKeys.BUTTON_DISABLED.equals(key))
                    return body.emphasis(body.bold(), true);
                return theme.style(key);
            };
            int mark = canvas.save();
            try {
                canvas.clip(clip);
                canvas.fill(bounds, body);
                int width = messageBounds.width();
                for (int line = 0; line < Math.min(messageBounds.height(), messageLines.size()); line++) {
                    String message = messageLines.get(line);
                    if (CellText.width(message) > width)
                        message = width == 0 ? "" : CellText.clip(message, 0, width - 1) + "…";
                    CellText.drawClipped(canvas, messageBounds.x(), messageBounds.y() + line, message, body, width);
                }
                boolean first = true;
                for (var action : banner.actions()) {
                    var target = scope.target(action).orElseThrow();
                    var widget = focus == null ? new WidgetState(first && state.focused() && target.focusEligible(),
                        state.enabled() && target.interactive())
                        : focus.peekContext(scope, action).map(FocusContext::widgetState)
                            .orElse(new WidgetState(false, state.enabled() && target.interactive()));
                    target.placement().paint(canvas, buttonTheme, widget);
                    first = false;
                }
            } finally { canvas.restore(mark); }
        }
    }
}
