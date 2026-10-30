package dev.tuiframe.widget;

import dev.tuiframe.input.KeyStroke;
import dev.tuiframe.input.TuiInputEvent;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Traverses dialog controls and an optional application-owned content area as one focus ring. */
public final class DialogFocusTraversal {
    private static final Set<KeyStroke.Modifier> SHIFT = Set.of(KeyStroke.Modifier.SHIFT);

    private DialogFocusTraversal() {
    }

    /** Only exact Tab and Shift+Tab participate; command-modified Tab remains application-owned. */
    public static boolean isTraversal(TuiInputEvent event) {
        return event instanceof KeyStroke stroke && stroke.key() == KeyStroke.Key.TAB
            && (stroke.modifiers().isEmpty() || stroke.modifiers().equals(SHIFT));
    }

    /**
     * Moves through eligible controls in scope order and then through the content area.
     *
     * <p>Controls must satisfy {@link RoutedTarget#focusEligible()}: enabled, focusable,
     * nonempty and fully inside their parent clip unless they declare intersection visibility.
     * Hidden content is skipped. Selecting content, or finding no available stop, explicitly
     * clears the control focus so placement reconciliation cannot steal content ownership.
     * Non-traversal keys leave both owners unchanged.</p>
     *
     * @return whether the content area owns focus after this event
     */
    public static <K, A> boolean advance(
        FocusManager<K> focus,
        FocusScope<K, A> scope,
        boolean contentFocused,
        boolean contentAvailable,
        KeyStroke stroke
    ) {
        Objects.requireNonNull(focus, "focus");
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(stroke, "stroke");
        if (!isTraversal(stroke)) {
            return contentFocused;
        }
        List<K> order = scope.tabOrder().stream()
            .filter(id -> eligible(scope.target(id).orElse(null)))
            .toList();
        K current = focus.activeScopeId().filter(scope.id()::equals).isPresent()
            ? focus.focused().orElse(null) : null;
        Target<K> next = navigate(order, current, contentFocused, contentAvailable,
            stroke.has(KeyStroke.Modifier.SHIFT));
        if (next.control() != null) {
            focus.focus(scope, next.control());
        } else {
            focus.clear(scope);
        }
        return next.contentFocused();
    }

    /** Clears any previous owner and focuses the first currently eligible control, if one exists. */
    public static <K, A> void focusFirst(FocusManager<K> focus, FocusScope<K, A> scope) {
        Objects.requireNonNull(focus, "focus");
        Objects.requireNonNull(scope, "scope");
        focus.clear(scope);
        for (K id : scope.tabOrder()) {
            if (eligible(scope.target(id).orElse(null))) {
                focus.focus(scope, id);
                return;
            }
        }
    }

    /**
     * Pure traversal for applications which retain their own focus IDs rather than a FocusManager.
     *
     * <p>The supplied order contains distinct, non-null controls already filtered for eligibility.
     * Content follows the last control and precedes the first. If the previous control has
     * disappeared, forward traversal starts at the first control and reverse at the last.
     * An empty order selects content only when content is available.</p>
     */
    public static <K> Target<K> navigate(
        List<K> eligibleOrder,
        K current,
        boolean contentFocused,
        boolean contentAvailable,
        boolean backwards
    ) {
        List<K> order = List.copyOf(Objects.requireNonNull(eligibleOrder, "eligibleOrder"));
        if (!order.isEmpty()) {
            int index = contentFocused || current == null ? -1 : order.indexOf(current);
            int next = index < 0 ? (backwards ? order.size() - 1 : 0)
                : index + (backwards ? -1 : 1);
            if (!contentAvailable) {
                next = Math.floorMod(next, order.size());
            }
            if (next >= 0 && next < order.size()) {
                return new Target<>(false, order.get(next));
            }
        }
        return new Target<>(contentAvailable, null);
    }

    /** A content stop, a control stop, or no available stop ({@code false, null}). */
    public record Target<K>(boolean contentFocused, K control) {
        public Target {
            if (contentFocused && control != null) {
                throw new IllegalArgumentException("Content and a control cannot both own focus");
            }
        }
    }

    private static boolean eligible(RoutedTarget<?, ?> target) {
        return target != null && target.focusEligible();
    }
}
