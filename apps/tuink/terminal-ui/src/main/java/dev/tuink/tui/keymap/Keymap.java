package dev.tuink.tui.keymap;

import dev.tuink.tui.command.CommandInvocation;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * TUInk-owned mapping from physical key values to semantic command invocations.
 *
 * <p>The key type is generic so the mapping stays independent of JLine and can
 * adopt the physical {@code KeyStroke} contract without changing command APIs.</p>
 */
public final class Keymap<K> {
    private final Map<K, CommandInvocation> bindings = new LinkedHashMap<>();

    /**
     * Adds a binding without silently replacing an existing one.
     *
     * @throws IllegalStateException when the key is already bound
     */
    public synchronized void bind(K key, CommandInvocation invocation) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(invocation, "invocation");
        if (bindings.containsKey(key)) {
            throw new IllegalStateException("Key is already bound: " + key);
        }
        bindings.put(key, invocation);
    }

    /** Explicitly creates or replaces a binding and returns the previous value. */
    public synchronized Optional<CommandInvocation> rebind(K key, CommandInvocation invocation) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(invocation, "invocation");
        return Optional.ofNullable(bindings.put(key, invocation));
    }

    public synchronized Optional<CommandInvocation> resolve(K key) {
        Objects.requireNonNull(key, "key");
        return Optional.ofNullable(bindings.get(key));
    }

    public synchronized Optional<CommandInvocation> unbind(K key) {
        Objects.requireNonNull(key, "key");
        return Optional.ofNullable(bindings.remove(key));
    }

    /** Returns an immutable binding snapshot in declaration order. */
    public synchronized Map<K, CommandInvocation> bindings() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(bindings));
    }

    public synchronized int size() {
        return bindings.size();
    }
}
