package dev.tuiframe.widget;

import dev.tuiframe.geometry.Point;
import dev.tuiframe.input.KeyStroke;
import dev.tuiframe.input.PointerEvent;
import dev.tuiframe.input.ResizeEvent;
import dev.tuiframe.input.TuiInputEvent;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Flat immediate-mode focus owner. Targets remain in the immutable scope
 * snapshot supplied by the application; this manager retains only scope/focus
 * identity and explicit-clear state across frames.
 */
public final class FocusManager<K> {
    private Object activeScopeId;
    private K focused;
    private boolean activeScope;
    private boolean explicitlyCleared;

    public Optional<Object> activeScopeId() {
        return activeScope ? Optional.of(activeScopeId) : Optional.empty();
    }

    public Optional<K> focused() {
        return Optional.ofNullable(focused);
    }

    /** Enters/reconciles the supplied scope and returns this target's current context. */
    public <A> Optional<FocusContext> context(FocusScope<K, A> scope, K id) {
        FocusScope<K, A> required = Objects.requireNonNull(scope, "scope");
        activate(required);
        return peekContext(required, id);
    }

    /**
     * Reads this frame's target context without activating or reconciling its
     * scope. Targets in other scopes are unfocused; current placement determines
     * availability. A missing or unavailable target leaves retained focus intact.
     */
    public <A> Optional<FocusContext> peekContext(FocusScope<K, A> scope, K id) {
        FocusScope<K, A> required = Objects.requireNonNull(scope, "scope");
        boolean active = activeScope && Objects.equals(activeScopeId, required.id());
        return required.target(Objects.requireNonNull(id, "id"))
            .map(target -> target.context(active && Objects.equals(focused, target.id())));
    }

    public <A> boolean focus(FocusScope<K, A> scope, K id) {
        FocusScope<K, A> required = Objects.requireNonNull(scope, "scope");
        boolean changed = activate(required);
        RoutedTarget<K, A> target = required.target(Objects.requireNonNull(id, "id")).orElse(null);
        if (!eligible(target)) {
            return changed;
        }
        boolean targetChanged = !Objects.equals(focused, id);
        focused = id;
        explicitlyCleared = false;
        return changed || targetChanged;
    }

    public <A> boolean next(FocusScope<K, A> scope) {
        FocusScope<K, A> required = Objects.requireNonNull(scope, "scope");
        return activate(required) | move(required, 1).changed();
    }

    public <A> boolean previous(FocusScope<K, A> scope) {
        FocusScope<K, A> required = Objects.requireNonNull(scope, "scope");
        return activate(required) | move(required, -1).changed();
    }

    public <A> boolean clear(FocusScope<K, A> scope) {
        FocusScope<K, A> required = Objects.requireNonNull(scope, "scope");
        boolean changed = activate(required) || focused != null;
        focused = null;
        explicitlyCleared = true;
        return changed;
    }

    /** Applies a widget focus effect against the same scope snapshot used for routing. */
    public <A> boolean apply(FocusScope<K, A> scope, K self, FocusRequest request) {
        Objects.requireNonNull(request, "request");
        return switch (request) {
            case NONE -> activate(Objects.requireNonNull(scope, "scope"));
            case SELF -> focus(scope, Objects.requireNonNull(self, "self"));
            case NEXT -> next(scope);
            case PREVIOUS -> previous(scope);
            case CLEAR -> clear(scope);
        };
    }

    /** Explicitly leaves a scope so its reset policy is reapplied on re-entry. */
    public boolean deactivate(Object scopeId) {
        Objects.requireNonNull(scopeId, "scopeId");
        if (!activeScope || !Objects.equals(activeScopeId, scopeId)) {
            return false;
        }
        activeScope = false;
        activeScopeId = null;
        focused = null;
        explicitlyCleared = false;
        return true;
    }

    /** Routes focus and returns the typed target from this exact frame snapshot. */
    public <A> FocusRoute<K, A> route(FocusScope<K, A> scope, TuiInputEvent event) {
        FocusScope<K, A> required = Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(event, "event");
        boolean activated = activate(required);
        if (event instanceof ResizeEvent) {
            return emptyRoute(activated, false);
        }
        if (event instanceof KeyStroke stroke && stroke.key() == KeyStroke.Key.TAB) {
            int direction;
            if (stroke.modifiers().isEmpty()) {
                direction = 1;
            } else if (stroke.modifiers().equals(Set.of(KeyStroke.Modifier.SHIFT))) {
                direction = -1;
            } else {
                return routeFocused(required, activated, false);
            }
            Move move = move(required, direction);
            return move.handled()
                ? routeFocused(required, activated || move.changed(), true)
                : emptyRoute(activated, false);
        }
        if (event instanceof PointerEvent pointer) {
            K before = focused;
            for (K targetId : required.pointerTopmostFirst()) {
                RoutedTarget<K, A> target = required.target(targetId).orElseThrow();
                Optional<Point> local = target.space().localPoint(pointer);
                if (local.isEmpty() || !target.placement().hit(local.orElseThrow())) {
                    continue;
                }
                if (pointer.kind() == PointerEvent.Kind.PRESS
                    && pointer.button() == PointerEvent.Button.LEFT
                    && eligible(target)) {
                    focus(required, target.id());
                }
                return routeTo(
                    target,
                    !Objects.equals(before, focused) || activated,
                    false
                );
            }
            return emptyRoute(activated, false);
        }
        return routeFocused(required, activated, false);
    }

    private <A> boolean activate(FocusScope<K, A> scope) {
        K before = focused;
        boolean scopeChanged = !activeScope || !Objects.equals(activeScopeId, scope.id());
        if (scopeChanged) {
            activeScope = true;
            activeScopeId = scope.id();
            explicitlyCleared = scope.resetPolicy() == FocusResetPolicy.CLEARED;
            focused = explicitlyCleared ? null : firstFocusableId(scope).orElse(null);
        } else if (!eligible(target(scope, focused))) {
            // A dialog may deliberately start cleared so a disappearing safe
            // choice must not silently transfer Enter/Space to its first action.
            if (scope.resetPolicy() == FocusResetPolicy.CLEARED) {
                explicitlyCleared = true;
            }
            focused = explicitlyCleared ? null : firstFocusableId(scope).orElse(null);
        }
        return scopeChanged || !Objects.equals(before, focused);
    }

    private <A> FocusRoute<K, A> routeFocused(
        FocusScope<K, A> scope,
        boolean changed,
        boolean navigationConsumed
    ) {
        RoutedTarget<K, A> target = target(scope, focused);
        return target == null
            ? emptyRoute(changed, navigationConsumed)
            : routeTo(target, changed, navigationConsumed);
    }

    private <A> FocusRoute<K, A> routeTo(
        RoutedTarget<K, A> target,
        boolean changed,
        boolean navigationConsumed
    ) {
        return new FocusRoute<>(
            Optional.of(target),
            Optional.of(target.context(Objects.equals(focused, target.id()))),
            changed,
            navigationConsumed
        );
    }

    private <A> FocusRoute<K, A> emptyRoute(boolean changed, boolean navigationConsumed) {
        return new FocusRoute<>(Optional.empty(), Optional.empty(), changed, navigationConsumed);
    }

    private <A> Move move(FocusScope<K, A> scope, int direction) {
        List<K> order = scope.tabOrder();
        int count = order.size();
        if (count == 0) {
            return Move.NOT_HANDLED;
        }
        int current = -1;
        for (int index = 0; index < count; index++) {
            if (Objects.equals(order.get(index), focused)) {
                current = index;
                break;
            }
        }
        for (int offset = 1; offset <= count; offset++) {
            int base = current < 0 ? (direction > 0 ? -1 : 0) : current;
            int index = Math.floorMod(base + direction * offset, count);
            K candidateId = order.get(index);
            if (eligible(scope.target(candidateId).orElse(null))) {
                boolean changed = !Objects.equals(focused, candidateId);
                focused = candidateId;
                explicitlyCleared = false;
                return new Move(true, changed);
            }
        }
        return Move.NOT_HANDLED;
    }

    private static <K, A> Optional<K> firstFocusableId(FocusScope<K, A> scope) {
        return scope.tabOrder().stream()
            .filter(id -> eligible(scope.target(id).orElse(null)))
            .findFirst();
    }

    private static <K, A> RoutedTarget<K, A> target(FocusScope<K, A> scope, K id) {
        return id == null ? null : scope.target(id).orElse(null);
    }

    private static boolean eligible(RoutedTarget<?, ?> target) {
        return target != null && target.focusEligible();
    }

    private record Move(boolean handled, boolean changed) {
        private static final Move NOT_HANDLED = new Move(false, false);
    }
}
