package dev.tuink.tui;

import dev.tuiframe.cell.Canvas;
import dev.tuiframe.cell.CellText;
import dev.tuiframe.explorer.file.PathLabels;
import dev.tuiframe.geometry.Point;
import dev.tuiframe.geometry.Rect;
import dev.tuiframe.input.KeyStroke;
import dev.tuiframe.input.PointerEvent;
import dev.tuiframe.input.TuiInputEvent;
import dev.tuiframe.text.TerminalPlainText;
import dev.tuiframe.widget.*;
import dev.tuink.editor.search.SearchOptions;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import static dev.tuink.tui.ProjectSearchPanel.Target;
import static dev.tuink.tui.ProjectSearchPanel.Kind;

/** Responsive search form and virtual result presentation. Layout and paint do not compile or scan queries. */
final class ProjectSearchView {
    private ProjectSearchView() {}
    private static final int WIDE_MODES = 45;
    record Label(int y, String text) {}
    record Popup(Target target, PlacedPopup<DropDown.Action> placement) {}
    record StatusLine(int y, String text, WidgetStyleRole role) {}
    record Spec(Target id, String label, Kind action, boolean enabled, Boolean checked) {
        Spec(Target id, String label, Kind action, boolean enabled) { this(id, label, action, enabled, null); }
    }

    static int minimumHeight(ProjectSearchPanel state, int paletteWidth) {
        int width = Math.max(0, paletteWidth - 2);
        int height = 2 + rows(optionSpecs(state, width), width) + rows(actionSpecs(state, width, null), width) + 5;
        if (state.filters) height += (width < 23 ? 4 : 2) + rows(filterFlags(state), width) + 1 + (state.scope == null ? 0 : 1);
        return state.hintsVisible ? height : height - 1;
    }
    private static List<Spec> optionSpecs(ProjectSearchPanel p, int width) {
        ArrayList<Spec> result = new ArrayList<>();
        if (width >= WIDE_MODES) for (var mode : SearchOptions.MatchMode.values())
            result.add(new Spec(ProjectSearchPanel.modeTarget(mode), mode.label(), Kind.CHANGED, true, p.mode == mode));
        else result.add(new Spec(Target.MODE, p.mode.label() + " ▾", Kind.NONE, true));
        result.add(new Spec(Target.CASE, "Aa", Kind.CHANGED, true, p.caseSensitive));
        result.add(new Spec(Target.WORD, "Word", Kind.CHANGED, true, p.wholeWord));
        return result;
    }
    private static List<Spec> actionSpecs(ProjectSearchPanel p, int width, Path root) {
        int count = ProjectSearchPresentation.activeFilters(p, root);
        String label = (p.filters ? "▾ " : "▸ ") + "Filters" + (width >= 33 ? " · " + count + " active" : width >= 20 ? " · " + count : ":" + count);
        boolean busy = p.running || p.pending;
        return List.of(new Spec(Target.FILTERS, label, Kind.NONE, true),
            new Spec(Target.FIND, busy ? "Stop" : p.stale ? "Refresh" : "Search", busy ? Kind.STOP : Kind.FIND, busy || !p.query.isEmpty()));
    }
    private static List<Spec> filterFlags(ProjectSearchPanel p) {
        return List.of(new Spec(Target.IGNORE, "Add gitignore", Kind.CHANGED, true, !p.respectIgnore),
            new Spec(Target.HIDDEN, "Add hidden", Kind.CHANGED, true, p.hidden));
    }
    private static int rows(List<Spec> specs, int width) {
        int rows = 1, used = 0;
        for (var spec : specs) {
            int cells = buttonWidth(spec);
            if (used > 0 && used + cells + 1 > width) { rows++; used = 0; }
            used += cells + (used == 0 ? 0 : 1);
        }
        return rows;
    }
    private static InteractiveWidget<?> control(Spec spec) {
        return spec.checked() == null ? new Button(spec.label()) : new CheckButton(spec.label(), spec.checked());
    }
    private static int buttonWidth(Spec spec) {
        return spec.id() == Target.FIND ? new Button("Refresh").width()
            : control(spec).measure(new dev.tuiframe.geometry.Size(Integer.MAX_VALUE, 1)).width();
    }
    static Placement place(ProjectSearchPanel p, Rect bounds, boolean focused, boolean interactive, Path root) {
        p.modes.setValue(p.mode); p.encodings.setValue(p.encoding);
        boolean compact = bounds.height() < 7;
        Builder form = new Builder(p, bounds, interactive, compact);
        p.wideModes = form.width >= WIDE_MODES;
        if (!focused || !interactive || compact) p.dismissPopups();
        else if (p.wideModes) p.modes.dismissPopup();
        if (!p.filters) p.encodings.dismissPopup();
        if (root == null) {
            form.label("Search in files");
            form.buttons(List.of(new Spec(Target.ALL_FOLDER, "Open folder...", Kind.OPEN_FOLDER, true)));
        } else {
            if (!compact) form.label("Folder: " + (p.scope == null || p.scope.equals(root) ? PathLabels.name(root) : PathLabels.relative(root, p.scope)));
            form.field(Target.QUERY, p.query, "Find: ", "Search text…");
            if (compact) form.buttons(List.of(actionSpecs(p, form.width, root).getLast()));
            else {
                form.buttons(optionSpecs(p, form.width));
                form.buttons(actionSpecs(p, form.width, root));
                if (p.filters) {
                    form.field(Target.INCLUDES, p.includes, "Include: ", "e.g. *.java, *.md");
                    form.field(Target.EXCLUDES, p.excludes, "Exclude: ", "e.g. build/**, *.log");
                    form.buttons(filterFlags(p));
                    form.encoding();
                    if (p.scope != null && !p.scope.equals(root)) form.buttons(List.of(new Spec(Target.ALL_FOLDER, "Whole folder", Kind.ALL_FOLDER, true)));
                }
            }
        }
        var status = ProjectSearchPresentation.status(p);
        ArrayList<StatusLine> statusLines = new ArrayList<>();
        int statusY = Math.min(form.y, Math.max(bounds.y(), form.footer - 1));
        if (compact) statusY = Math.max(bounds.y(), bounds.bottomExclusive() - 1);
        String prefix = status.role().equals(WidgetThemeKeys.NOTICE_ERROR) || status.role().equals(WidgetThemeKeys.NOTICE_WARNING) ? "! " : "";
        if (bounds.height() > 0) statusLines.add(new StatusLine(statusY++, root == null ? "Open a folder to search" : prefix + status.title(), status.role()));
        int detailRoom = Math.min(2, Math.max(0, form.footer - statusY - (p.rows.isEmpty() ? 0 : 2)));
        String detail = status.detail();
        if (detail.startsWith(status.title())) detail = detail.substring(status.title().length()).strip();
        for (String line : wrap(detail, form.width, detailRoom)) statusLines.add(new StatusLine(statusY++, line, status.role()));
        if (!compact && p.skipped > 0 && statusY < form.footer) {
            form.y = statusY;
            form.buttons(List.of(new Spec(Target.ISSUES, p.showIssues ? "Show results" : "Skipped: " + p.skipped, Kind.NONE, true)));
            statusY = form.y;
        }
        int listTop = Math.min(form.footer, statusY);
        Rect listBounds = new Rect(form.x, listTop, form.width, compact ? 0 : Math.max(0, form.footer - listTop));
        var list = new ListView(p.rows.size(), ignored -> "", p.listState).layout(listBounds);
        p.listState = list.state();
        form.targets.add(new RoutedTarget<>(Target.RESULTS, list.mapOutput(p::adoptList), form.space, true,
            interactive && listBounds.height() > 0));
        if (form.popups.stream().noneMatch(popup -> popup.target() == Target.MODE)) p.modes.dismissPopup();
        if (form.popups.stream().noneMatch(popup -> popup.target() == Target.ENCODING)) p.encodings.dismissPopup();
        var ids = form.targets.stream().map(RoutedTarget::id).toList();
        var scope = new FocusScope<>(p, FocusResetPolicy.FIRST_FOCUSABLE, form.targets, ids, ids);
        p.focus.context(scope, Target.QUERY);
        if (p.requestedFocus != null && focused) {
            if (ids.contains(p.requestedFocus)) p.focus.focus(scope, p.requestedFocus);
            p.requestedFocus = null;
        }
        String hint = ProjectSearchPresentation.hint(p, root);
        return new Placement(p, bounds, scope, list, List.copyOf(form.labels), List.copyOf(form.popups),
            ProjectSearchResults.snapshot(p, list), List.copyOf(statusLines), form.bodyLayout.hintBounds().isEmpty() ? null : new Label(form.footer, hint),
            form.bodyLayout, focused, interactive);
    }
    private static List<String> wrap(String source, int width, int maximum) {
        if (source.isEmpty() || width <= 0 || maximum <= 0) return List.of();
        ArrayList<String> lines = new ArrayList<>();
        String text = TerminalPlainText.singleLine(source);
        int cells = CellText.width(text);
        for (int offset = 0; offset < cells && lines.size() < maximum; offset += width) lines.add(CellText.clip(text, offset, width));
        return lines;
    }
    private static final class Builder {
        final ProjectSearchPanel state;
        final Rect bounds;
        final FocusSpace space;
        final boolean interactive;
        final int x, width, footer, controlBottom;
        final ToolPalette.BodyLayout bodyLayout;
        final ArrayList<RoutedTarget<Target, Kind>> targets = new ArrayList<>();
        final ArrayList<Label> labels = new ArrayList<>();
        final ArrayList<Popup> popups = new ArrayList<>();
        int y;
        Builder(ProjectSearchPanel state, Rect bounds, boolean interactive, boolean compact) {
            this.state = state; this.bounds = bounds; this.interactive = interactive; space = FocusSpace.root(bounds);
            x = bounds.x() + Math.min(1, bounds.width()); width = Math.max(0, bounds.width() - 2); y = bounds.y();
            bodyLayout = ToolPalette.bodyLayout(new Rect(x, bounds.y(), width, bounds.height()), state.hintsVisible, 6);
            footer = bodyLayout.contentBounds().bottomExclusive();
            controlBottom = compact ? Math.max(bounds.y(), bounds.bottomExclusive() - 1) : Math.max(bounds.y(), footer - 1);
        }
        void label(String text) { if (y < controlBottom) labels.add(new Label(y, TerminalPlainText.singleLine(text))); y++; }
        void field(Target id, TextField field, String label, String placeholder) {
            if (CellText.width(label) > width - 8) { label(label.strip()); label = ""; }
            if (y < controlBottom && width - CellText.width(label) >= 3) {
                if (!label.isEmpty()) labels.add(new Label(y, label));
                int start = x + CellText.width(label);
                targets.add(new RoutedTarget<>(id, field.placeholder(placeholder).layout(new Rect(start, y, x + width - start, 1)).mapOutput(action -> switch (action) {
                    case VALUE_CHANGED -> Kind.CHANGED; case SUBMITTED -> Kind.FIND; case INPUT_REJECTED -> Kind.REJECTED;
                }), space, true, interactive));
            }
            y++;
        }
        void buttons(List<Spec> specs) {
            int column = x;
            for (var spec : specs) {
                int cells = buttonWidth(spec);
                if (column > x && column + cells > x + width) { y++; column = x; }
                button(spec, column, y, width - (column - x)); column += cells + 1;
            }
            y++;
        }
        void button(Spec spec, int column, int row, int available) {
            if (row >= controlBottom || buttonWidth(spec) > available) return;
            Rect rect = new Rect(column, row, buttonWidth(spec), 1);
            if (spec.id() == Target.MODE) dropDown(Target.MODE, state.modes, rect, value -> state.mode = value);
            else targets.add(new RoutedTarget<>(spec.id(), control(spec).layout(rect).mapOutput(ignored -> spec.action()),
                space, true, interactive && spec.enabled()));
        }
        void encoding() {
            String label = width >= 22 ? "Encoding: " : "Enc: ";
            int cells = CellText.width(label);
            if (y < controlBottom && cells + state.encodings.width() <= width) {
                labels.add(new Label(y, label));
                dropDown(Target.ENCODING, state.encodings, new Rect(x + cells, y, state.encodings.width(), 1), value -> state.encoding = value);
            } else state.encodings.dismissPopup();
            y++;
        }
        <T> void dropDown(Target id, DropDown<T> widget, Rect rect, java.util.function.Consumer<T> selected) {
            var placed = widget.layout(rect, bounds);
            popups.add(new Popup(id, placed));
            targets.add(new RoutedTarget<>(id, placed.mapOutput(action -> {
                if (action != DropDown.Action.VALUE_CHANGED) return Kind.NONE;
                selected.accept(widget.value()); return Kind.CHANGED;
            }), space, true, interactive));
        }
    }
    record Placement(ProjectSearchPanel state, Rect bounds, FocusScope<Target, Kind> scope, ListView.Placement list,
                     List<Label> labels, List<Popup> popups, List<ProjectSearchResults.Line> resultRows,
                     List<StatusLine> status, Label footer, ToolPalette.BodyLayout bodyLayout, boolean focused, boolean interactive) {
        boolean popupOpen() { return popups.stream().anyMatch(popup -> popup.placement().popupOpen()); }
        Optional<Point> cursor() {
            if (!focused || !interactive || popupOpen()) return Optional.empty();
            Target id = state.focus.focused().orElse(null);
            var target = id == null ? null : scope.target(id).orElse(null);
            var context = id == null ? null : state.focus.peekContext(scope, id).orElse(null);
            if (target == null || context == null || !(target.placement() instanceof PlacedCursorSource cursor)) return Optional.empty();
            return cursor.cursorPosition(context).flatMap(context.space()::terminalPoint);
        }
        Kind handle(TuiInputEvent event, WindowPointerCapture capture, Object surfaceId) {
            if (!interactive) return Kind.NONE;
            if (popupOpen()) {
                var popup = popups.stream().filter(item -> item.placement().popupOpen()).findFirst().orElseThrow();
                return scope.target(popup.target()).orElseThrow().placement()
                    .handle(event, new FocusContext(true, true, FocusSpace.root(bounds))).output().orElse(Kind.NONE);
            }
            state.hoveredTarget = event instanceof PointerEvent pointer ? scope.targets().stream()
                .filter(target -> target.placement().bounds().contains(pointer.column(), pointer.row())).map(RoutedTarget::id).findFirst().orElse(null) : null;
            if (event instanceof KeyStroke key && key.modifiers().isEmpty()) {
                if (key.key() == KeyStroke.Key.ARROW_DOWN && state.focus.focused().orElse(null) == Target.QUERY) {
                    state.focus.focus(scope, Target.RESULTS); return Kind.NONE;
                }
                if (state.focus.focused().orElse(null) == Target.RESULTS && (key.key() == KeyStroke.Key.ARROW_LEFT || key.key() == KeyStroke.Key.ARROW_RIGHT)) {
                    state.toggleSelected(key.key() == KeyStroke.Key.ARROW_RIGHT); return Kind.NONE;
                }
            }
            var route = state.focus.route(scope, event);
            if (route.navigationConsumed() || route.target().isEmpty()) return Kind.NONE;
            var id = route.target().orElseThrow().id();
            var result = FieldInputDispatcher.event(state.focusedField(), event, 4096,
                input -> route.target().orElseThrow().placement().handle(input, route.context().orElseThrow()), Kind.REJECTED);
            state.focus.apply(scope, id, result.focusRequest()); capture.observe(surfaceId, scope, id, result);
            if (result.output().isEmpty()) return Kind.NONE;
            switch (id) {
                case MODE_STRICT, MODE_WILDCARD, MODE_REGEX -> { return state.chooseMode(ProjectSearchPanel.targetMode(id)); }
                case CASE -> state.caseSensitive = !state.caseSensitive;
                case WORD -> state.wholeWord = !state.wholeWord;
                case FILTERS -> state.filters = !state.filters;
                case IGNORE -> state.respectIgnore = !state.respectIgnore;
                case HIDDEN -> state.hidden = !state.hidden;
                case ISSUES -> { state.showIssues = !state.showIssues; state.rebuild(); }
                default -> { }
            }
            return result.output().orElse(Kind.NONE);
        }
        void paint(Canvas canvas, Theme theme) {
            var body = theme.resolveStyle(WidgetThemeKeys.MODAL_BODY);
            var muted = theme.resolveStyle(WidgetThemeKeys.TEXT_MUTED, body);
            canvas.fill(bounds, body);
            for (var label : labels) CellText.drawClipped(canvas, bounds.x() + 1, label.y(), label.text(), muted, Math.max(0, bounds.width() - 2));
            for (var target : scope.targets()) {
                paintTarget(canvas, theme, target);
            }
            ProjectSearchResults.paint(canvas, theme, list, resultRows, focused && state.focus.focused().orElse(null) == Target.RESULTS, interactive);
            for (var line : status) message(canvas, line.y(), line.text(), theme.resolveStyle(line.role(), body));
            if (footer != null) bodyLayout.paintHint(canvas, theme, footer.text());
            for (var popup : popups) popup.placement().paintPopup(canvas, theme, new WidgetState(focused && interactive, interactive));
        }
        private void message(Canvas canvas, int row, String text, dev.tuiframe.style.CellStyle style) {
            int width = Math.max(0, bounds.width() - 2);
            canvas.fill(new Rect(bounds.x() + 1, row, width, 1), style);
            CellText.drawClipped(canvas, bounds.x() + 1, row, TerminalPlainText.singleLine(text), style, width);
        }
        private void paintTarget(Canvas canvas, Theme theme, RoutedTarget<Target, Kind> target) {
            if (target.id() == Target.RESULTS) return;
            var context = state.focus.peekContext(scope, target.id()).orElseThrow();
            boolean primary = target.id() == Target.FIND;
            var actionStyle = theme.resolveStyle(state.running || state.pending ? WidgetThemeKeys.BUTTON_WARNING : WidgetThemeKeys.BUTTON_PRIMARY,
                theme.resolveStyle(WidgetThemeKeys.BUTTON, theme.resolveStyle(WidgetThemeKeys.MODAL_BODY)));
            Theme choice = primary ? role -> {
                if (role.equals(WidgetThemeKeys.BUTTON)) return actionStyle;
                if (role.equals(WidgetThemeKeys.BUTTON_FOCUSED)) return actionStyle.inverted();
                if (role.equals(WidgetThemeKeys.BUTTON_DISABLED)) return actionStyle.emphasis(false, false);
                return theme.style(role);
            } : theme;
            target.placement().paint(canvas, choice, new WidgetState(focused && context.focused(), context.enabled()));
        }
    }
}
