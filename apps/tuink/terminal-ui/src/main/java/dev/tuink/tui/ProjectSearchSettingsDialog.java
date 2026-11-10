package dev.tuink.tui;

import dev.tuink.editor.storage.DocumentOpenMode;
import dev.tuink.project.search.SearchPreferences;
import dev.tuiframe.cell.Canvas;
import dev.tuiframe.cell.CellText;
import dev.tuiframe.geometry.Point;
import dev.tuiframe.geometry.Rect;
import dev.tuiframe.input.KeyStroke;
import dev.tuiframe.input.PointerEvent;
import dev.tuiframe.input.TuiInputEvent;
import dev.tuiframe.text.TerminalPlainText;
import dev.tuiframe.widget.Button;
import dev.tuiframe.widget.CheckButton;
import dev.tuiframe.widget.DropDown;
import dev.tuiframe.widget.FocusContext;
import dev.tuink.tui.ui.SearchControls;
import dev.tuiframe.widget.FocusManager;
import dev.tuiframe.widget.FocusResetPolicy;
import dev.tuiframe.widget.FocusScope;
import dev.tuiframe.widget.FocusSpace;
import dev.tuiframe.widget.Modal;
import dev.tuiframe.widget.PlacedModal;
import dev.tuiframe.widget.RoutedTarget;
import dev.tuiframe.widget.TextField;
import dev.tuiframe.widget.Theme;
import dev.tuiframe.widget.WidgetState;
import dev.tuiframe.widget.WidgetThemeKeys;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Retained preference draft, using the same widgets and modal routing as document dialogs. */
final class ProjectSearchSettingsDialog {
    enum Target { AUTOMATIC, IGNORE, HIDDEN, EXCLUSIONS, ENCODING, SIZE, MATCHES, APPLY, CANCEL }
    enum Action { NONE, APPLY, CANCEL }
    static final class State {
        boolean automatic, ignore, hidden;
        DocumentOpenMode encoding;
        final DropDown<DocumentOpenMode> encodings = SearchControls.encodings(DocumentOpenMode.DETECT);
        final TextField exclusions = new TextField();
        final TextField size = new TextField();
        final TextField matches = new TextField();
        final FocusManager<Target> focus = new FocusManager<>();
        String error;
        State(SearchPreferences preferences) {
            automatic = preferences.automatic(); ignore = preferences.respectIgnore(); hidden = preferences.hidden();
            encoding = preferences.encoding(); exclusions.set(preferences.exclusions());
            size.set(Integer.toString(preferences.maximumFileMiB())); matches.set(Integer.toString(preferences.maximumMatches()));
        }
        TextField field(Target target) { return target == Target.EXCLUSIONS ? exclusions : target == Target.SIZE ? size : target == Target.MATCHES ? matches : null; }
        TextField focusedField() { return field(focus.focused().orElse(null)); }
        SearchPreferences preferences() {
            return new SearchPreferences(automatic, ignore, hidden, exclusions.text(), encoding,
                Integer.parseInt(size.text()), Integer.parseInt(matches.text()));
        }
    }
    static Placement place(State state, Rect viewport) {
        state.encodings.setValue(state.encoding);
        int width = Math.min(66, viewport.width()), height = Math.min(16, viewport.height());
        Rect bounds = new Rect(viewport.x() + (viewport.width() - width) / 2,
            viewport.y() + (viewport.height() - height) / 2, width, height);
        var frame = new Modal(width, height, "Project search defaults").layout(bounds);
        var space = FocusSpace.root(viewport);
        ArrayList<RoutedTarget<Target, Runnable>> targets = new ArrayList<>();
        ArrayList<Label> labels = new ArrayList<>();
        boolean compact = width < 38 || height < 15;
        int x = bounds.x() + 2, y = bounds.y() + 1;
        DropDown<DocumentOpenMode>.Placement encoding = null;
        if (compact) state.encodings.dismissPopup();
        if (!compact) {
            check(targets, Target.AUTOMATIC, "Search while typing", state.automatic, () -> state.automatic = !state.automatic, x, y++, space);
            check(targets, Target.IGNORE, "Add gitignore", !state.ignore, () -> state.ignore = !state.ignore, x, y++, space);
            check(targets, Target.HIDDEN, "Add hidden", state.hidden, () -> state.hidden = !state.hidden, x, y++, space);
            field(targets, labels, Target.EXCLUSIONS, state.exclusions, "Exclude: ", x, y++, width - 4, space);
            labels.add(new Label(y, "Encoding: "));
            encoding = state.encodings.layout(new Rect(x + 10, y++, state.encodings.width(), 1), new Rect(bounds.x() + 1, bounds.y() + 1, Math.max(0, bounds.width() - 2), Math.max(0, bounds.height() - 2)));
            targets.add(new RoutedTarget<>(Target.ENCODING, encoding.mapOutput(action -> (Runnable) () -> {
                if (action == DropDown.Action.VALUE_CHANGED) state.encoding = state.encodings.value();
            }), space, true, true));
            labels.add(new Label(y++, "Advanced limits"));
            field(targets, labels, Target.SIZE, state.size, "File size (MiB, 1–64): ", x, y++, width - 4, space);
            field(targets, labels, Target.MATCHES, state.matches, "Results (1–100000): ", x, y++, width - 4, space);
        }
        int row = Math.max(bounds.y(), bounds.bottomExclusive() - 2);
        if (!compact) toggle(targets, Target.APPLY, "Apply", () -> {}, x, row, space);
        toggle(targets, Target.CANCEL, "Cancel", () -> {}, Math.max(bounds.x(), bounds.rightExclusive() - 12), row, space);
        var ids = targets.stream().map(RoutedTarget::id).toList();
        var scope = new FocusScope<>(state, FocusResetPolicy.FIRST_FOCUSABLE, targets, ids, ids);
        state.focus.context(scope, compact ? Target.CANCEL : Target.AUTOMATIC);
        return new Placement(state, bounds, frame, scope, labels, compact, encoding);
    }
    private static void toggle(ArrayList<RoutedTarget<Target, Runnable>> targets, Target id, String label, Runnable action,
                               int x, int y, FocusSpace space) {
        Button button = new Button(label);
        targets.add(new RoutedTarget<>(id, button.layout(new Rect(x, y, button.width(), 1)).mapOutput(ignored -> action), space, true, true));
    }
    private static void check(ArrayList<RoutedTarget<Target, Runnable>> targets, Target id, String label, boolean checked,
                              Runnable action, int x, int y, FocusSpace space) {
        var button = new CheckButton(label, checked);
        targets.add(new RoutedTarget<>(id, button.layout(new Rect(x, y, button.width(), 1)).mapOutput(ignored -> action), space, true, true));
    }
    private static void field(ArrayList<RoutedTarget<Target, Runnable>> targets, ArrayList<Label> labels,
                              Target id, TextField field, String label, int x, int y, int width, FocusSpace space) {
        labels.add(new Label(y, label)); int indent = CellText.width(label);
        targets.add(new RoutedTarget<>(id, field.layout(new Rect(x + indent, y, Math.max(3, width - indent), 1))
            .mapOutput(ignored -> (Runnable) () -> {}), space, true, true));
    }
    record Label(int y, String text) {}
    record Placement(State state, Rect bounds, PlacedModal frame, FocusScope<Target, Runnable> scope, List<Label> labels, boolean compact, DropDown<DocumentOpenMode>.Placement encoding) {
        boolean popupOpen() { return encoding != null && encoding.popupOpen(); }
        Optional<Point> cursor() {
            if (popupOpen()) return Optional.empty();
            Target id = state.focus.focused().orElse(null);
            var target = id == null ? null : scope.target(id).orElse(null);
            var context = id == null ? null : state.focus.peekContext(scope, id).orElse(null);
            if (target == null || context == null || !(target.placement() instanceof dev.tuiframe.widget.PlacedCursorSource cursor)) return Optional.empty();
            return cursor.cursorPosition(context).flatMap(context.space()::terminalPoint);
        }
        Action handle(TuiInputEvent event, WindowPointerCapture capture, Object surface) {
            if (popupOpen()) {
                var result = encoding.handle(event, new FocusContext(true, true, FocusSpace.root(bounds)));
                if (result.output().orElse(null) == DropDown.Action.VALUE_CHANGED) state.encoding = state.encodings.value();
                return Action.NONE;
            }
            if (event instanceof KeyStroke key && key.key() == KeyStroke.Key.ESCAPE) return Action.CANCEL;
            if (event instanceof PointerEvent pointer && pointer.kind() == PointerEvent.Kind.PRESS
                && pointer.button() == PointerEvent.Button.LEFT && frame.closeHit(pointer.row(), pointer.column())) return Action.CANCEL;
            var route = state.focus.route(scope, event);
            if (route.navigationConsumed() || route.target().isEmpty()) return Action.NONE;
            var target = route.target().orElseThrow();
            var result = FieldInputDispatcher.event(state.focusedField(), event, 4096,
                input -> target.placement().handle(input, route.context().orElseThrow()), (Runnable) () -> state.error = "Input too large");
            state.focus.apply(scope, target.id(), result.focusRequest());
            result.output().ifPresent(Runnable::run);
            capture.observe(surface, scope, target.id(), result);
            if (result.output().isPresent()) {
                if (target.id() == Target.CANCEL) return Action.CANCEL;
                if (target.id() == Target.APPLY) return Action.APPLY;
            }
            return Action.NONE;
        }
        void paint(Canvas canvas, Theme theme) {
            frame.paint(canvas, theme, WidgetState.NORMAL);
            var body = theme.resolveStyle(WidgetThemeKeys.MODAL_BODY);
            if (compact) CellText.drawClipped(canvas, bounds.x() + 1, bounds.y() + 1, "Enlarge terminal to 38x17", body, Math.max(0, bounds.width() - 2));
            else {
                for (var label : labels) CellText.drawClipped(canvas, bounds.x() + 2, label.y(), label.text(), body, bounds.width() - 4);
                String note = state.error == null ? "Current filters retained; typing and limits apply next run" : state.error;
                CellText.drawClipped(canvas, bounds.x() + 2, bounds.bottomExclusive() - 4,
                    TerminalPlainText.singleLine(note), body, Math.max(0, bounds.width() - 4));
            }
            for (var target : scope.targets()) target.placement().paint(canvas, theme,
                state.focus.peekContext(scope, target.id()).orElseThrow().widgetState());
            if (encoding != null) encoding.paintPopup(canvas, theme, WidgetState.FOCUSED);
        }
    }
}
