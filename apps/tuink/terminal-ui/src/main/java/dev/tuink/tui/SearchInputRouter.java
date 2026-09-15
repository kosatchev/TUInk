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
import dev.tuink.tui.flow.SearchFocus;
import dev.tuink.tui.ui.SearchBarView;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Raw-input adapter for the application-owned multi-control search bar. */
final class SearchInputRouter {
    private static final Map<SearchFocus, SearchFocus> VERTICAL_TARGET = Map.of(
        SearchFocus.FIND, SearchFocus.REPLACE,
        SearchFocus.REPLACE, SearchFocus.FIND,
        SearchFocus.CASE, SearchFocus.NEXT_BUTTON,
        SearchFocus.NEXT_BUTTON, SearchFocus.CASE,
        SearchFocus.WORD, SearchFocus.PREV_BUTTON,
        SearchFocus.PREV_BUTTON, SearchFocus.WORD,
        SearchFocus.WILDCARD, SearchFocus.REPLACE_BUTTON,
        SearchFocus.REPLACE_BUTTON, SearchFocus.WILDCARD,
        SearchFocus.ALL, SearchFocus.ALL_BUTTON,
        SearchFocus.ALL_BUTTON, SearchFocus.ALL
    );

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

    static Result route(
        FocusManager<SearchFocus> focusManager,
        SearchBarView.Placement placement,
        TuiInputEvent event
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
                return losesMeaningInLegacyAdapter(stroke.key())
                    ? Result.CAPTURED
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
                return routeVertical(focusManager, placement.focusScope());
            }
            if (stroke.key() == KeyStroke.Key.ARROW_LEFT
                || stroke.key() == KeyStroke.Key.ARROW_RIGHT) {
                return routeHorizontal(focusManager, placement, event, stroke.key());
            }
            if (stroke.key() == KeyStroke.Key.ENTER) {
                return routeEnter(focusManager);
            }
            if (stroke.key() == KeyStroke.Key.SPACE) {
                return routeSpace(focusManager);
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
                // A real non-field target keeps legacy Home/End bubbling, but
                // an entirely clipped scope must not fall through to the
                // legacy FIND fallback and move an invisible caret.
                return focused == null ? Result.CAPTURED : Result.IGNORED;
            }
        }

        if (event instanceof PasteEvent paste && paste.truncated()) {
            return Result.action(SearchBarView.Action.PASTE_REJECTED);
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
            if (" ".equals(text.text()) && isToggle(focused)) {
                return Result.action(actionFor(focused));
            }
            return Result.CAPTURED;
        }

        if (event instanceof PointerEvent) {
            return routePointer(focusManager, placement, event);
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
        FocusScope<SearchFocus, SearchBarView.Action> scope
    ) {
        SearchFocus current = focusManager.focused().orElse(null);
        if (current == null) {
            return Result.CAPTURED;
        }
        SearchFocus target = VERTICAL_TARGET.get(current);
        if (target != null) {
            focusManager.focus(scope, target);
        }
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

    private static Result routeEnter(FocusManager<SearchFocus> focusManager) {
        SearchFocus focused = focusManager.focused().orElse(null);
        if (focused == null) {
            return Result.CAPTURED;
        }
        if (isActionButton(focused)) {
            return Result.action(actionFor(focused));
        }
        // Enter on fields and option toggles means Find Next.
        return Result.action(SearchBarView.Action.FIND_NEXT);
    }

    private static Result routeSpace(FocusManager<SearchFocus> focusManager) {
        SearchFocus focused = focusManager.focused().orElse(null);
        // Space toggles options; action buttons do not activate on Space.
        // Enter activates the focused action button.
        return isToggle(focused)
            ? Result.action(actionFor(focused))
            : Result.CAPTURED;
    }

    private static Result routePointer(
        FocusManager<SearchFocus> focusManager,
        SearchBarView.Placement placement,
        TuiInputEvent event
    ) {
        FocusScope<SearchFocus, SearchBarView.Action> scope = placement.focusScope();
        FocusRoute<SearchFocus, SearchBarView.Action> route = focusManager.route(scope, event);
        if (route.target().isEmpty()) {
            return Result.IGNORED;
        }
        RoutedTarget<SearchFocus, SearchBarView.Action> target = route.target().orElseThrow();
        InteractionResult<SearchBarView.Action> interaction = target.placement()
            .handle(event, route.context().orElseThrow());
        focusManager.apply(scope, target.id(), interaction.focusRequest());
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
        InteractionResult<SearchBarView.Action> interaction = target.placement()
            .handle(event, route.context().orElseThrow());
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

    /** Prevents the transitional adapter from discarding modifiers and editing/submitting. */
    private static boolean losesMeaningInLegacyAdapter(KeyStroke.Key key) {
        return key == KeyStroke.Key.ESCAPE
            || key == KeyStroke.Key.ENTER
            || key == KeyStroke.Key.SPACE
            || key == KeyStroke.Key.BACKSPACE
            || key == KeyStroke.Key.DELETE
            || key == KeyStroke.Key.HOME
            || key == KeyStroke.Key.END;
    }

    private static boolean isField(SearchFocus focus) {
        return focus == SearchFocus.FIND || focus == SearchFocus.REPLACE;
    }

    private static boolean isToggle(SearchFocus focus) {
        return focus == SearchFocus.CASE
            || focus == SearchFocus.WORD
            || focus == SearchFocus.WILDCARD
            || focus == SearchFocus.ALL;
    }

    private static boolean isActionButton(SearchFocus focus) {
        return focus == SearchFocus.NEXT_BUTTON
            || focus == SearchFocus.PREV_BUTTON
            || focus == SearchFocus.REPLACE_BUTTON
            || focus == SearchFocus.ALL_BUTTON;
    }

    private static SearchBarView.Action actionFor(SearchFocus focus) {
        return switch (Objects.requireNonNull(focus, "focus")) {
            case CASE -> SearchBarView.Action.TOGGLE_CASE;
            case WORD -> SearchBarView.Action.TOGGLE_WORD;
            case WILDCARD -> SearchBarView.Action.TOGGLE_WILDCARD;
            case ALL -> SearchBarView.Action.TOGGLE_ALL_TABS;
            case NEXT_BUTTON -> SearchBarView.Action.FIND_NEXT;
            case PREV_BUTTON -> SearchBarView.Action.FIND_PREVIOUS;
            case REPLACE_BUTTON -> SearchBarView.Action.REPLACE;
            case ALL_BUTTON -> SearchBarView.Action.REPLACE_ALL;
            case FIND, REPLACE -> throw new IllegalArgumentException("field has no button action");
        };
    }
}
