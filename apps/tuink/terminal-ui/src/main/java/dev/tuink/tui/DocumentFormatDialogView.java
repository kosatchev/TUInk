package dev.tuink.tui;

import dev.tuink.editor.format.DocumentEncoding;
import dev.tuink.editor.format.DocumentFormatSpec;
import dev.tuink.editor.format.DocumentFormatSummary;
import dev.tuink.editor.format.LineEndingPolicy;
import dev.tuink.editor.storage.DocumentOpenMode;
import dev.tuiframe.cell.Canvas;
import dev.tuiframe.cell.CellText;
import dev.tuiframe.geometry.Rect;
import dev.tuiframe.input.KeyStroke;
import dev.tuiframe.input.PointerEvent;
import dev.tuiframe.input.TuiInputEvent;
import dev.tuiframe.text.TerminalPlainText;
import dev.tuiframe.widget.Button;
import dev.tuiframe.widget.FocusManager;
import dev.tuiframe.widget.FocusResetPolicy;
import dev.tuiframe.widget.FocusScope;
import dev.tuiframe.widget.FocusSpace;
import dev.tuiframe.widget.ListView;
import dev.tuiframe.widget.Modal;
import dev.tuiframe.widget.PlacedModal;
import dev.tuiframe.widget.RoutedTarget;
import dev.tuiframe.widget.Theme;
import dev.tuiframe.widget.WidgetState;
import dev.tuiframe.widget.WidgetThemeKeys;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** One retained draft and exact-frame placement for conversion and explicit decoding. */
final class DocumentFormatDialogView {
    enum Purpose { FORMAT, OPEN, REOPEN }
    enum Target { ENCODING, EOL, APPLY, CANCEL }
    enum Action { NONE, APPLY, CANCEL }

    static final class State {
        final Purpose purpose;
        final List<DocumentEncoding> encodings;
        final List<String> labels;
        final FocusManager<Target> focus = new FocusManager<>();
        final String currentDescription;
        ListView.State encodingList;
        ListView.State eolList = new ListView.State(0, 0);
        Target requestedFocus;
        boolean busy;
        boolean confirmDiscard;
        String error;
        private boolean compact;
        private Target focusBeforeCompact;

        State(Purpose purpose, DocumentFormatSummary summary, Target initialFocus) {
            this.purpose = purpose;
            ArrayList<DocumentEncoding> values = new ArrayList<>();
            ArrayList<String> names = new ArrayList<>();
            if (purpose != Purpose.FORMAT || summary.encoding() == null) {
                values.add(null);
                names.add(purpose == Purpose.FORMAT ? "Current: " + summary.charsetName() : "Auto");
            }
            for (DocumentEncoding encoding : DocumentEncoding.values()) {
                if (purpose != Purpose.FORMAT && encoding == DocumentEncoding.UTF8_BOM) continue;
                values.add(encoding);
                names.add(purpose != Purpose.FORMAT && encoding == DocumentEncoding.UTF16LE_BOM ? "UTF-16LE"
                    : purpose != Purpose.FORMAT && encoding == DocumentEncoding.UTF16BE_BOM ? "UTF-16BE" : encoding.displayName());
            }
            encodings = Collections.unmodifiableList(values);
            labels = List.copyOf(names);
            DocumentEncoding selected = summary.encoding();
            if (purpose != Purpose.FORMAT && selected == DocumentEncoding.UTF8_BOM) selected = DocumentEncoding.UTF8;
            int index = purpose == Purpose.OPEN ? 0 : Math.max(0, values.indexOf(selected));
            encodingList = new ListView.State(index, 0);
            requestedFocus = purpose == Purpose.FORMAT ? initialFocus : Target.ENCODING;
            currentDescription = "Current: " + summary.lineEnding() + "; new lines: "
                + switch (summary.defaultLineSeparator()) { case "\r\n" -> "CRLF"; case "\r" -> "CR"; default -> "LF"; };
        }

        DocumentFormatSpec specification() {
            return new DocumentFormatSpec(encodings.get(encodingList.selectedIndex()),
                LineEndingPolicy.values()[eolList.selectedIndex()]);
        }

        DocumentOpenMode openMode() {
            DocumentEncoding encoding = encodings.get(encodingList.selectedIndex());
            if (encoding == null) return DocumentOpenMode.DETECT;
            return switch (encoding) {
                case UTF8, UTF8_BOM -> DocumentOpenMode.UTF_8;
                case UTF16LE_BOM -> DocumentOpenMode.UTF_16LE;
                case UTF16BE_BOM -> DocumentOpenMode.UTF_16BE;
                case WINDOWS_1251 -> DocumentOpenMode.WINDOWS_1251;
                case CP866 -> DocumentOpenMode.CP866;
                case KOI8_R -> DocumentOpenMode.KOI8_R;
            };
        }

        void adopt(Target target, ListView.Action action) {
            ListView.State selected = action instanceof ListView.StateChanged changed ? changed.state()
                : action instanceof ListView.ItemPressed pressed ? pressed.state() : null;
            if (selected != null) {
                if (target == Target.ENCODING) encodingList = selected;
                else eolList = selected;
                error = null;
                confirmDiscard = false;
            }
        }
    }

    static Placement place(State state, Rect viewport, boolean applyAllowed) {
        boolean compact = viewport.width() < 44 || viewport.height() < 11;
        int width = Math.min(66, viewport.width());
        int height = Math.min(13, viewport.height());
        Rect bounds = new Rect(viewport.x() + (viewport.width() - width) / 2,
            viewport.y() + (viewport.height() - height) / 2, width, height);
        String title = switch (state.purpose) {
            case FORMAT -> "Document format"; case OPEN -> "Open with encoding"; case REOPEN -> "Reopen with encoding";
        };
        PlacedModal frame = new Modal(width, height, title).layout(bounds);
        FocusSpace space = FocusSpace.root(viewport);
        ArrayList<RoutedTarget<Target, Runnable>> targets = new ArrayList<>();
        ListView.Placement encodings = null;
        ListView.Placement endings = null;
        int contentRows = Math.max(0, height - 6);
        int encodingWidth = state.purpose == Purpose.FORMAT ? Math.max(1, (width - 5) / 2) : Math.max(1, width - 4);
        if (!compact) {
            encodings = new ListView(state.labels.size(), state.labels::get, state.encodingList)
                .layout(new Rect(bounds.x() + 2, bounds.y() + 2, encodingWidth, contentRows));
            state.encodingList = encodings.state();
            targets.add(new RoutedTarget<>(Target.ENCODING,
                encodings.mapOutput(action -> (Runnable) () -> state.adopt(Target.ENCODING, action)),
                space, true, !state.busy));
            if (state.purpose == Purpose.FORMAT) {
                List<String> labels = List.of("Keep existing", "LF", "CRLF", "CR");
                endings = new ListView(labels.size(), labels::get, state.eolList)
                    .layout(new Rect(bounds.x() + encodingWidth + 3, bounds.y() + 2,
                        Math.max(1, width - encodingWidth - 5), contentRows));
                state.eolList = endings.state();
                targets.add(new RoutedTarget<>(Target.EOL,
                    endings.mapOutput(action -> (Runnable) () -> state.adopt(Target.EOL, action)),
                    space, true, !state.busy));
            }
        }
        String primary = state.purpose == Purpose.FORMAT ? "Apply"
            : state.purpose == Purpose.OPEN ? "Choose file" : state.confirmDiscard ? "Discard and reopen" : "Reopen";
        Button apply = new Button(primary);
        Button cancel = new Button("Cancel");
        int buttonRow = Math.max(bounds.y(), bounds.bottomExclusive() - 2);
        int cancelX = Math.max(bounds.x(), bounds.rightExclusive() - cancel.width() - 2);
        int applyX = Math.max(bounds.x(), cancelX - apply.width() - 2);
        if (!compact) {
            targets.add(new RoutedTarget<>(Target.APPLY, apply.layout(new Rect(applyX, buttonRow, apply.width(), 1))
                .mapOutput(ignored -> (Runnable) () -> {}), space, true, applyAllowed && !state.busy));
        }
        int cancelWidth = Math.min(cancel.width(), bounds.width());
        targets.add(new RoutedTarget<>(Target.CANCEL,
            cancel.layout(new Rect(Math.min(cancelX, Math.max(bounds.x(), bounds.rightExclusive() - cancelWidth)),
                buttonRow, cancelWidth, Math.min(1, bounds.height())))
                .mapOutput(ignored -> (Runnable) () -> {}), space, true, bounds.width() >= cancel.width()));
        List<Target> order = compact ? List.of(Target.CANCEL)
            : state.purpose == Purpose.FORMAT ? List.of(Target.ENCODING, Target.EOL, Target.APPLY, Target.CANCEL)
            : List.of(Target.ENCODING, Target.APPLY, Target.CANCEL);
        FocusScope<Target, Runnable> scope = new FocusScope<>(state, FocusResetPolicy.FIRST_FOCUSABLE,
            targets, order, order);
        if (compact && !state.compact) state.focusBeforeCompact = state.focus.focused().orElse(state.requestedFocus);
        state.focus.context(scope, compact ? Target.CANCEL : Target.ENCODING);
        if (!compact && state.requestedFocus != null) {
            state.focus.focus(scope, state.requestedFocus);
            state.requestedFocus = null;
        } else if (!compact && state.compact && state.focusBeforeCompact != null) {
            state.focus.focus(scope, state.focusBeforeCompact);
        }
        state.compact = compact;
        return new Placement(state, frame, scope, encodings, endings, compact, encodingWidth);
    }

    record Placement(State state, PlacedModal frame, FocusScope<Target, Runnable> scope,
                     ListView.Placement encodings, ListView.Placement endings, boolean compact, int encodingWidth) {
        Rect bounds() { return frame.bounds(); }

        Action handle(TuiInputEvent event, WindowPointerCapture capture, Object surfaceId) {
            if (event instanceof KeyStroke key && key.modifiers().isEmpty() && key.key() == KeyStroke.Key.ESCAPE) {
                return Action.CANCEL;
            }
            if (event instanceof PointerEvent pointer && pointer.kind() == PointerEvent.Kind.PRESS
                && pointer.button() == PointerEvent.Button.LEFT && frame.closeHit(pointer.row(), pointer.column())) {
                return Action.CANCEL;
            }
            var route = state.focus.route(scope, event);
            if (route.navigationConsumed() || route.target().isEmpty()) return Action.NONE;
            Target target = route.target().orElseThrow().id();
            var result = route.target().orElseThrow().placement().handle(event, route.context().orElseThrow());
            state.focus.apply(scope, target, result.focusRequest());
            result.output().ifPresent(Runnable::run);
            capture.observe(surfaceId, scope, target, result);
            if (result.output().isPresent()) {
                if (target == Target.CANCEL) return Action.CANCEL;
                if (target == Target.APPLY) return Action.APPLY;
            }
            if (event instanceof KeyStroke key && key.modifiers().isEmpty() && key.key() == KeyStroke.Key.ENTER
                && (target == Target.ENCODING || target == Target.EOL)) state.focus.focus(scope, Target.APPLY);
            return Action.NONE;
        }

        void paint(Canvas canvas, Theme theme) {
            frame.paint(canvas, theme, WidgetState.NORMAL);
            var body = theme.resolveStyle(WidgetThemeKeys.MODAL_BODY);
            if (compact) {
                CellText.drawClipped(canvas, bounds().x() + 1, bounds().y() + 1,
                    "Enlarge terminal to 44x11", body, Math.max(0, bounds().width() - 2));
            } else {
                CellText.drawClipped(canvas, bounds().x() + 2, bounds().y() + 1, "Encoding", body, encodingWidth);
                if (state.purpose == Purpose.FORMAT) CellText.drawClipped(canvas, bounds().x() + encodingWidth + 3,
                    bounds().y() + 1, "Line endings", body, Math.max(0, bounds().width() - encodingWidth - 5));
                String note = state.busy ? state.purpose == Purpose.FORMAT ? "Checking format; Esc cancels"
                    : "Reading document; Esc cancels" : state.error != null ? state.error
                    : state.confirmDiscard ? "Unsaved edits will be discarded. Cancel keeps them."
                    : state.purpose == Purpose.FORMAT ? state.currentDescription : "Read original file bytes with the selected encoding";
                CellText.drawClipped(canvas, bounds().x() + 2, bounds().bottomExclusive() - 4,
                    TerminalPlainText.singleLine(note), body, Math.max(0, bounds().width() - 4));
            }
            for (Target id : scope.tabOrder()) {
                var target = scope.target(id).orElseThrow();
                var context = state.focus.peekContext(scope, id).orElseThrow();
                target.placement().paint(canvas, theme, context.widgetState());
            }
        }
    }
}
