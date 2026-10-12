package dev.tuink.tui;

import dev.tuiframe.geometry.Rect;
import dev.tuiframe.input.PointerEvent;
import dev.tuiframe.input.TuiInputEvent;
import dev.tuiframe.widget.InteractionResult;
import dev.tuiframe.widget.TabBar;
import dev.tuink.tui.ui.TabBarView;
import java.util.Objects;

/** Raw pointer adapter and owner-policy gate for the TUInk tab bar. */
final class TabBarInputRouter {
    enum Policy {
        NORMAL,
        SEARCH,
        CLOSE_TAB_CONFIRM
    }

    record Result(
        TabBar.State state,
        Integer activateIndex,
        Integer closeIndex,
        boolean consumed,
        boolean blocked
    ) {
        Result {
            Objects.requireNonNull(state, "state");
            if (activateIndex != null && activateIndex < 0) {
                throw new IllegalArgumentException("activateIndex must not be negative");
            }
            if (closeIndex != null && closeIndex < 0) {
                throw new IllegalArgumentException("closeIndex must not be negative");
            }
            if (activateIndex != null && closeIndex != null) {
                throw new IllegalArgumentException("one event cannot activate and close a tab");
            }
            if (!consumed && (activateIndex != null || closeIndex != null)) {
                throw new IllegalArgumentException("an ignored event cannot request a tab decision");
            }
            if (blocked && (!consumed || activateIndex != null || closeIndex != null)) {
                throw new IllegalArgumentException("blocked input must be consumed without a tab decision");
            }
        }
    }

    private TabBarInputRouter() {
    }

    static Result route(TabBarView.Placement placement, Policy policy, TuiInputEvent event) {
        Objects.requireNonNull(placement, "placement");
        Policy requiredPolicy = Objects.requireNonNull(policy, "policy");
        Objects.requireNonNull(event, "event");
        TabBar.State current = placement.state();

        // Keyboard tab commands remain in TUInk's global command/keymap path.
        if (!(event instanceof PointerEvent pointer)) {
            return ignored(current);
        }
        if (!pointer.modifiers().isEmpty()) {
            return belongsToTabChrome(placement, pointer)
                ? captured(current)
                : ignored(current);
        }

        PointerEvent routedPointer = routeChromeWheel(placement, pointer);
        InteractionResult<TabBar.Action> interaction = placement.handle(routedPointer);
        if (interaction.output().isPresent()) {
            TabBar.Action action = interaction.output().orElseThrow();
            if (action instanceof TabBar.StateChanged changed) {
                if (requiredPolicy == Policy.SEARCH && !isVerticalWheel(pointer)) {
                    return captured(current);
                }
                return captured(changed.state());
            }
            if (action instanceof TabBar.TabPressed pressed) {
                return requiredPolicy == Policy.NORMAL
                    ? activate(pressed.state(), pressed.tabIndex())
                    : blocked(current);
            }
            if (action instanceof TabBar.ClosePressed pressed) {
                return requiredPolicy == Policy.NORMAL
                    ? close(pressed.state(), pressed.tabIndex())
                    : blocked(current);
            }
            throw new IllegalStateException("Unsupported tab-bar action: " + action);
        }
        if (interaction.consumed()) {
            return captured(current);
        }
        if ((requiredPolicy == Policy.SEARCH || requiredPolicy == Policy.CLOSE_TAB_CONFIRM)
            && placement.contains(pointer)) {
            // The bar remains visible behind these owners, but a tab/close hit
            // must never fall through into an editor or a silent close path.
            return captured(current);
        }
        return ignored(current);
    }

    private static boolean isVerticalWheel(PointerEvent pointer) {
        return pointer.kind() == PointerEvent.Kind.SCROLL_UP
            || pointer.kind() == PointerEvent.Kind.SCROLL_DOWN;
    }

    private static boolean belongsToTabChrome(
        TabBarView.Placement placement,
        PointerEvent pointer
    ) {
        if (placement.contains(pointer)) {
            return true;
        }
        Rect bounds = placement.bounds();
        return isVerticalWheel(pointer)
            && bounds.y() > 0
            && pointer.row() == bounds.y() - 1
            && pointer.column() >= bounds.x()
            && pointer.column() < bounds.rightExclusive();
    }

    private static PointerEvent routeChromeWheel(
        TabBarView.Placement placement,
        PointerEvent pointer
    ) {
        if (!isVerticalWheel(pointer)) {
            return pointer;
        }
        int tabRow = placement.bounds().y();
        if (tabRow <= 0
            || pointer.row() != tabRow - 1
            || pointer.column() < placement.bounds().x()
            || pointer.column() >= placement.bounds().rightExclusive()) {
            return pointer;
        }
        return new PointerEvent(
            tabRow,
            pointer.column(),
            pointer.kind(),
            pointer.button(),
            pointer.modifiers()
        );
    }

    private static Result ignored(TabBar.State state) {
        return new Result(state, null, null, false, false);
    }

    private static Result captured(TabBar.State state) {
        return new Result(state, null, null, true, false);
    }

    private static Result blocked(TabBar.State state) {
        return new Result(state, null, null, true, true);
    }

    private static Result activate(TabBar.State state, int index) {
        return new Result(state, index, null, true, false);
    }

    private static Result close(TabBar.State state, int index) {
        return new Result(state, null, index, true, false);
    }
}
