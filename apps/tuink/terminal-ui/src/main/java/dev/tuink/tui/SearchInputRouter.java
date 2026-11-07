package dev.tuink.tui;

import dev.tuiframe.input.KeyStroke;
import dev.tuiframe.input.PasteEvent;
import dev.tuiframe.input.PointerEvent;
import dev.tuiframe.input.TextInput;
import dev.tuiframe.input.TuiInputEvent;
import dev.tuiframe.widget.FocusManager;
import dev.tuiframe.widget.FocusRoute;
import dev.tuiframe.widget.FocusScope;
import dev.tuiframe.widget.InteractionResult;
import dev.tuiframe.widget.RoutedTarget;
import dev.tuiframe.widget.TextField;
import dev.tuink.tui.flow.SearchFocus;
import dev.tuink.tui.ui.SearchBarView;
import dev.tuink.tui.command.TuinkCommand;
import dev.tuink.core.EditorClipboard;
import java.util.Objects;
import java.util.Set;

/** Raw-input adapter for the application-owned multi-control search bar. */
final class SearchInputRouter {
    record Result(boolean consumed, SearchBarView.Action action) {
        private static final Result IGNORED = new Result(false, null);
        private static final Result CAPTURED = new Result(true, null);

        Result {
            if (!consumed && action != null) {
                throw new IllegalArgumentException("an ignored event cannot produce a search action");
            }
        }

        static Result action(SearchBarView.Action action) {
            return new Result(true, Objects.requireNonNull(action, "action"));
        }
    }

    private SearchInputRouter() {
    }

    static Result routeCommand(FocusManager<SearchFocus> focusManager, SearchBarView.Placement placement,
        TuinkCommand command, EditorClipboard clipboard) {
        Objects.requireNonNull(focusManager, "focusManager");
        Objects.requireNonNull(placement, "placement");
        Objects.requireNonNull(command, "command");
        placement.activate(focusManager);
        SearchFocus focused = focusManager.focused().orElse(null);
        var editing = FieldInputDispatcher.command(command, placement.field(focused).orElse(null),
            clipboard, queryLimit(placement));
        if (editing instanceof FieldInputDispatcher.Applied applied) {
            if (applied.inputRejected()) return Result.action(SearchBarView.Action.INPUT_REJECTED);
            return applied.valueChanged() ? Result.action(focused == SearchFocus.REPLACE
                ? SearchBarView.Action.REPLACEMENT_CHANGED : SearchBarView.Action.QUERY_CHANGED) : Result.CAPTURED;
        }
        // Search page keys belong to the document viewport, independently of field focus.
        if (command == TuinkCommand.CURSOR_PAGE_UP || command == TuinkCommand.CURSOR_PAGE_DOWN) {
            return Result.IGNORED;
        }
        SearchBarView.Action intent = switch (command) {
            case SEARCH_NEXT -> SearchBarView.Action.FIND_NEXT;
            case SEARCH_PREVIOUS -> SearchBarView.Action.FIND_PREVIOUS;
            case SEARCH_REPLACE -> placement.readOnly() ? null : SearchBarView.Action.REPLACE;
            case SEARCH_REPLACE_ALL -> placement.readOnly() ? null : SearchBarView.Action.REPLACE_ALL;
            default -> null;
        };
        if (placement.readOnly() && (command == TuinkCommand.SEARCH_REPLACE || command == TuinkCommand.SEARCH_REPLACE_ALL))
            return Result.CAPTURED;
        if (intent != null) {
            return Result.action(intent);
        }
        if (editing instanceof FieldInputDispatcher.Forward forward) {
            Result routed = route(focusManager, placement, forward.event());
            return routed.consumed() ? routed : Result.CAPTURED;
        }
        return Result.IGNORED;
    }

    static Result route(
        FocusManager<SearchFocus> focusManager,
        SearchBarView.Placement placement,
        TuiInputEvent event
    ) {
        return route(focusManager, placement, event, null, null);
    }

    static Result route(
        FocusManager<SearchFocus> focusManager,
        SearchBarView.Placement placement,
        TuiInputEvent event,
        WindowPointerCapture capture,
        Object surfaceId
    ) {
        Objects.requireNonNull(focusManager, "focusManager");
        Objects.requireNonNull(placement, "placement");
        Objects.requireNonNull(event, "event");
        placement.activate(focusManager);

        if (event instanceof KeyStroke stroke) {
            if (isExactShiftTab(stroke)) {
                return routeTab(focusManager, placement, event);
            }
            if (!stroke.modifiers().isEmpty()) {
                if ((stroke.key() == KeyStroke.Key.PAGE_UP || stroke.key() == KeyStroke.Key.PAGE_DOWN)
                    && stroke.has(KeyStroke.Modifier.SHIFT)) {
                    return Result.CAPTURED;
                }
                return TextField.ownsEditingKey(stroke)
                    ? isField(focusManager.focused().orElse(null))
                        ? routeFocused(focusManager, placement, event, true) : Result.CAPTURED
                    : Result.IGNORED;
            }
            if (stroke.key() == KeyStroke.Key.TAB) {
                return routeTab(focusManager, placement, event);
            }
            if (stroke.key() == KeyStroke.Key.ESCAPE) {
                return Result.action(SearchBarView.Action.CLOSE_REQUESTED);
            }
            if (stroke.key() == KeyStroke.Key.ARROW_UP
                || stroke.key() == KeyStroke.Key.ARROW_DOWN) {
                return routeVertical(focusManager, placement, stroke.key());
            }
            if (stroke.key() == KeyStroke.Key.ARROW_LEFT
                || stroke.key() == KeyStroke.Key.ARROW_RIGHT) {
                return routeHorizontal(focusManager, placement, event, stroke.key());
            }
            if (stroke.key() == KeyStroke.Key.ENTER) {
                return routeFocused(focusManager, placement, event, true);
            }
            if (stroke.key() == KeyStroke.Key.SPACE) {
                return routeFocused(focusManager, placement, event, true);
            }
            if (stroke.key() == KeyStroke.Key.BACKSPACE
                || stroke.key() == KeyStroke.Key.DELETE) {
                return isField(focusManager.focused().orElse(null))
                    ? routeFocused(focusManager, placement, event, true)
                    : Result.CAPTURED;
            }
            if (stroke.key() == KeyStroke.Key.HOME || stroke.key() == KeyStroke.Key.END) {
                SearchFocus focused = focusManager.focused().orElse(null);
                if (isField(focused)) {
                    return routeFocused(focusManager, placement, event, true);
                }
                return Result.CAPTURED;
            }
        }

        if (event instanceof PasteEvent paste && paste.truncated()) {
            return Result.action(SearchBarView.Action.INPUT_REJECTED);
        }

        if (event instanceof PasteEvent) {
            return isField(focusManager.focused().orElse(null))
                ? routeFocused(focusManager, placement, event, true)
                : Result.CAPTURED;
        }

        if (event instanceof TextInput text) {
            SearchFocus focused = focusManager.focused().orElse(null);
            if (isField(focused)) {
                return routeFocused(focusManager, placement, event, true);
            }
            if (" ".equals(text.text())) {
                return routeFocused(focusManager, placement, event, true);
            }
            return Result.CAPTURED;
        }

        if (event instanceof PointerEvent) {
            return routePointer(focusManager, placement, event, capture, surfaceId);
        }

        return Result.IGNORED;
    }

    private static Result routeTab(
        FocusManager<SearchFocus> focusManager,
        SearchBarView.Placement placement,
        TuiInputEvent event
    ) {
        focusManager.route(placement.focusScope(), event);
        return Result.CAPTURED;
    }

    private static Result routeVertical(
        FocusManager<SearchFocus> focusManager,
        SearchBarView.Placement placement,
        KeyStroke.Key key
    ) {
        SearchFocus current = focusManager.focused().orElse(null);
        if (current == null) {
            return Result.CAPTURED;
        }
        if (isField(current)) {
            return Result.CAPTURED;
        }
        focusManager.focus(placement.focusScope(), placement.verticalTarget(current, key == KeyStroke.Key.ARROW_UP));
        return Result.CAPTURED;
    }

    private static Result routeHorizontal(
        FocusManager<SearchFocus> focusManager,
        SearchBarView.Placement placement,
        TuiInputEvent event,
        KeyStroke.Key key
    ) {
        Result handled = routeFocused(focusManager, placement, event, false);
        if (handled.consumed()) {
            return handled;
        }
        if (key == KeyStroke.Key.ARROW_LEFT) {
            focusManager.previous(placement.focusScope());
        } else {
            focusManager.next(placement.focusScope());
        }
        return Result.CAPTURED;
    }

    private static Result routePointer(
        FocusManager<SearchFocus> focusManager,
        SearchBarView.Placement placement,
        TuiInputEvent event,
        WindowPointerCapture capture,
        Object surfaceId
    ) {
        FocusScope<SearchFocus, SearchBarView.Action> scope = placement.focusScope();
        FocusRoute<SearchFocus, SearchBarView.Action> route = focusManager.route(scope, event);
        if (route.target().isEmpty()) {
            return Result.IGNORED;
        }
        RoutedTarget<SearchFocus, SearchBarView.Action> target = route.target().orElseThrow();
        InteractionResult<SearchBarView.Action> interaction = FieldInputDispatcher.event(
            placement.field(target.id()).orElse(null), event, queryLimit(placement),
            input -> target.placement().handle(input, route.context().orElseThrow()), SearchBarView.Action.INPUT_REJECTED);
        focusManager.apply(scope, target.id(), interaction.focusRequest());
        if (capture != null) {
            capture.observe(surfaceId, scope, target.id(), interaction);
        }
        // A pointer miss may reach the editor, but every search-control hit is
        // modal, including a disabled partial target or an ignored release.
        return fromInteraction(interaction, true);
    }

    private static Result routeFocused(
        FocusManager<SearchFocus> focusManager,
        SearchBarView.Placement placement,
        TuiInputEvent event,
        boolean captureIgnored
    ) {
        FocusScope<SearchFocus, SearchBarView.Action> scope = placement.focusScope();
        FocusRoute<SearchFocus, SearchBarView.Action> route = focusManager.route(scope, event);
        if (route.target().isEmpty()) {
            return captureIgnored ? Result.CAPTURED : Result.IGNORED;
        }
        RoutedTarget<SearchFocus, SearchBarView.Action> target = route.target().orElseThrow();
        InteractionResult<SearchBarView.Action> interaction = FieldInputDispatcher.event(
            placement.field(target.id()).orElse(null), event, queryLimit(placement),
            input -> target.placement().handle(input, route.context().orElseThrow()), SearchBarView.Action.INPUT_REJECTED);
        focusManager.apply(scope, target.id(), interaction.focusRequest());
        return fromInteraction(interaction, captureIgnored);
    }

    private static Result fromInteraction(
        InteractionResult<SearchBarView.Action> interaction,
        boolean captureIgnored
    ) {
        if (interaction.output().isPresent()) {
            return Result.action(interaction.output().orElseThrow());
        }
        if (interaction.consumed() || captureIgnored) {
            return Result.CAPTURED;
        }
        return Result.IGNORED;
    }

    private static boolean isExactShiftTab(KeyStroke stroke) {
        return stroke.key() == KeyStroke.Key.TAB
            && stroke.modifiers().equals(Set.of(KeyStroke.Modifier.SHIFT));
    }

    private static boolean isField(SearchFocus focus) {
        return focus == SearchFocus.FIND || focus == SearchFocus.REPLACE;
    }

    private static int queryLimit(SearchBarView.Placement placement) {
        return placement.readOnly() ? MarkdownPreviewState.SEARCH_QUERY_MAX_UTF16 : FieldInputDispatcher.MEMORY_BOUNDED;
    }
}
