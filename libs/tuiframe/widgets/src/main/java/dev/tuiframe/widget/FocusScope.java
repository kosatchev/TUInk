package dev.tuiframe.widget;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable current-frame routing snapshot. Keyboard traversal and pointer
 * stacking are independent orders over the same typed targets.
 */
public final class FocusScope<K, A> {
    private final Object id;
    private final FocusResetPolicy resetPolicy;
    private final Map<K, RoutedTarget<K, A>> targets;
    private final List<K> tabOrder;
    private final List<K> pointerTopmostFirst;

    public FocusScope(
        Object id,
        FocusResetPolicy resetPolicy,
        List<RoutedTarget<K, A>> targets,
        List<K> tabOrder,
        List<K> pointerTopmostFirst
    ) {
        this.id = Objects.requireNonNull(id, "id");
        this.resetPolicy = Objects.requireNonNull(resetPolicy, "resetPolicy");
        Objects.requireNonNull(targets, "targets");
        LinkedHashMap<K, RoutedTarget<K, A>> indexed = new LinkedHashMap<>();
        for (RoutedTarget<K, A> target : List.copyOf(targets)) {
            Objects.requireNonNull(target, "target");
            if (indexed.putIfAbsent(target.id(), target) != null) {
                throw new IllegalArgumentException("duplicate routed target id: " + target.id());
            }
        }
        this.targets = Collections.unmodifiableMap(indexed);
        this.tabOrder = copyOrder("tabOrder", tabOrder, indexed);
        this.pointerTopmostFirst = copyOrder(
            "pointerTopmostFirst",
            pointerTopmostFirst,
            indexed
        );
    }

    public static <K, A> FocusScope<K, A> single(
        Object id,
        FocusResetPolicy resetPolicy,
        RoutedTarget<K, A> target
    ) {
        RoutedTarget<K, A> required = Objects.requireNonNull(target, "target");
        return new FocusScope<>(
            id,
            resetPolicy,
            List.of(required),
            List.of(required.id()),
            List.of(required.id())
        );
    }

    public Object id() {
        return id;
    }

    public FocusResetPolicy resetPolicy() {
        return resetPolicy;
    }

    public List<K> tabOrder() {
        return tabOrder;
    }

    /** Pointer order is explicit and starts with the visually topmost target. */
    public List<K> pointerTopmostFirst() {
        return pointerTopmostFirst;
    }

    public Optional<RoutedTarget<K, A>> target(K targetId) {
        return Optional.ofNullable(targets.get(Objects.requireNonNull(targetId, "targetId")));
    }

    private static <K, A> List<K> copyOrder(
        String name,
        List<K> order,
        Map<K, RoutedTarget<K, A>> targets
    ) {
        Objects.requireNonNull(order, name);
        List<K> copied = List.copyOf(order);
        LinkedHashMap<K, Boolean> seen = new LinkedHashMap<>();
        for (K targetId : copied) {
            Objects.requireNonNull(targetId, name + " target id");
            if (!targets.containsKey(targetId)) {
                throw new IllegalArgumentException(name + " contains unknown target id: " + targetId);
            }
            if (seen.putIfAbsent(targetId, Boolean.TRUE) != null) {
                throw new IllegalArgumentException(name + " contains duplicate target id: " + targetId);
            }
        }
        return copied;
    }
}
