package dev.tuiframe.widget;

import dev.tuiframe.input.PointerEvent;
import dev.tuiframe.input.ResizeEvent;
import dev.tuiframe.input.TuiInputEvent;
import java.util.List;
import java.util.HashSet;
import java.util.Objects;
import java.util.Optional;

/**
 * Pointer delivery across current-frame scopes. Only stable identities survive
 * between frames; placements, coordinates and cancellation outputs are always
 * obtained from the snapshot supplied by the caller.
 *
 * <p>Adopt every cancellation output before rebuilding its scope. In particular,
 * cancel before removing an owner, changing surfaces, focus or geometry. A
 * missing owner is reported as a lifecycle error rather than retaining a stale
 * placement or silently abandoning its gesture data.
 */
public final class PointerCaptureManager<K> {
    private Address<K> captured;

    public Optional<Address<K>> captured() {
        return Optional.ofNullable(captured);
    }

    /** Applies the capture effect after ordinary widget output has been adopted. */
    public <A> void apply(
        Object surfaceId,
        Object scopeId,
        RoutedTarget<K, A> target,
        InteractionResult<A> result
    ) {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(result, "result");
        Address<K> address = new Address<>(surfaceId, scopeId, target.id());
        switch (result.pointerCaptureRequest()) {
            case NONE -> { }
            case ACQUIRE -> {
                capability(target);
                if (captured != null && !captured.equals(address)) {
                    throw new IllegalStateException("cancel the previous pointer owner before acquiring another");
                }
                captured = address;
            }
            case RELEASE -> {
                if (address.equals(captured)) {
                    captured = null;
                }
            }
        }
    }

    /**
     * Returns the current owner for drag/release without hit testing or clipping.
     * The caller supplies this target's actual keyboard-focus context to handle.
     */
    public <A> Optional<Target<K, A>> route(
        Object surfaceId,
        List<FocusScope<K, A>> scopes,
        PointerEvent event
    ) {
        Objects.requireNonNull(event, "event");
        if (captured == null || !(event.kind() == PointerEvent.Kind.RELEASE
            || event.kind() == PointerEvent.Kind.DRAG && event.button() == PointerEvent.Button.LEFT)) {
            return Optional.empty();
        }
        Target<K, A> owner = owner(surfaceId, scopes);
        if (!owner.target().enabled() || !visible(owner.target())) {
            throw new IllegalStateException("reconcile the pointer owner before routing disabled or hidden targets");
        }
        if (!capability(owner.target()).pointerCaptureActive()) {
            captured = null;
            return Optional.empty();
        }
        return Optional.of(owner);
    }

    /** Common keyboard, resize and new-press boundaries, before normal routing. */
    public <A> Optional<Cancellation<K, A>> beforeInput(
        Object surfaceId,
        List<FocusScope<K, A>> scopes,
        TuiInputEvent event
    ) {
        Objects.requireNonNull(event, "event");
        if (event instanceof ResizeEvent) {
            return cancel(surfaceId, scopes, PointerCaptureCancellation.RESIZE);
        }
        if (event instanceof PointerEvent pointer) {
            return pointer.kind() == PointerEvent.Kind.PRESS
                ? cancel(surfaceId, scopes, PointerCaptureCancellation.NEW_PRESS)
                : reconcile(surfaceId, scopes);
        }
        return cancel(surfaceId, scopes, PointerCaptureCancellation.KEYBOARD_INPUT);
    }

    /** Cancels disabled/hidden owners while their current binding is still available. */
    public <A> Optional<Cancellation<K, A>> reconcile(
        Object surfaceId,
        List<FocusScope<K, A>> scopes
    ) {
        if (captured == null) {
            return Optional.empty();
        }
        Target<K, A> owner = owner(surfaceId, scopes);
        if (!owner.target().enabled()) {
            return cancel(surfaceId, scopes, PointerCaptureCancellation.DISABLED);
        }
        if (!visible(owner.target())) {
            return cancel(surfaceId, scopes, PointerCaptureCancellation.HIDDEN);
        }
        if (!capability(owner.target()).pointerCaptureActive()) {
            captured = null;
        }
        return Optional.empty();
    }

    /** Clear delivery and ask the current owner to end its gesture. */
    public <A> Optional<Cancellation<K, A>> cancel(
        Object surfaceId,
        List<FocusScope<K, A>> scopes,
        PointerCaptureCancellation reason
    ) {
        Objects.requireNonNull(reason, "reason");
        if (captured == null) {
            return Optional.empty();
        }
        Target<K, A> owner = owner(surfaceId, scopes);
        captured = null;
        InteractionResult<A> result = capability(owner.target()).cancelPointerCapture(reason);
        if (result.pointerCaptureRequest() == PointerCaptureRequest.ACQUIRE) {
            throw new IllegalStateException("cancelling a gesture cannot acquire pointer capture");
        }
        return Optional.of(new Cancellation<>(owner, result));
    }

    private <A> Target<K, A> owner(Object surfaceId, List<FocusScope<K, A>> scopes) {
        Objects.requireNonNull(surfaceId, "surfaceId");
        Objects.requireNonNull(scopes, "scopes");
        if (!Objects.equals(captured.surfaceId(), surfaceId)) {
            throw new IllegalStateException("cancel pointer capture before changing the active surface");
        }
        HashSet<Object> scopeIds = new HashSet<>();
        for (FocusScope<K, A> scope : scopes) {
            if (!scopeIds.add(scope.id())) {
                throw new IllegalArgumentException("duplicate pointer scope id: " + scope.id());
            }
        }
        for (FocusScope<K, A> scope : scopes) {
            if (Objects.equals(scope.id(), captured.scopeId())) {
                RoutedTarget<K, A> target = scope.target(captured.targetId()).orElseThrow(() ->
                    new IllegalStateException("cancel pointer capture before removing its target"));
                capability(target);
                return new Target<>(scope.id(), target);
            }
        }
        throw new IllegalStateException("cancel pointer capture before removing its scope");
    }

    private static boolean visible(RoutedTarget<?, ?> target) {
        return InteractionVisibility.VISIBLE_INTERSECTION.allows(target.placement().bounds(), target.space().parentClip());
    }

    @SuppressWarnings("unchecked")
    private static <A> PlacedPointerCapture<A> capability(RoutedTarget<?, A> target) {
        if (!(target.placement() instanceof PlacedPointerCapture<?> capture)) {
            throw new IllegalArgumentException("pointer owner must implement PlacedPointerCapture");
        }
        return (PlacedPointerCapture<A>) capture;
    }

    public record Address<K>(Object surfaceId, Object scopeId, K targetId) {
        public Address {
            Objects.requireNonNull(surfaceId, "surfaceId");
            Objects.requireNonNull(scopeId, "scopeId");
            Objects.requireNonNull(targetId, "targetId");
        }
    }

    public record Target<K, A>(Object scopeId, RoutedTarget<K, A> target) {
        public Target {
            Objects.requireNonNull(scopeId, "scopeId");
            Objects.requireNonNull(target, "target");
        }

        /**
         * Capture continuation uses visible intersection even when normal input
         * requires full bounds. Keep the caller's actual keyboard-focus state.
         * Individual widgets may impose stricter geometry and cancel their gesture.
         */
        public FocusContext context(boolean focused) {
            boolean available = target.enabled() && visible(target);
            return new FocusContext(focused && available, available, target.space());
        }
    }

    public record Cancellation<K, A>(Target<K, A> owner, InteractionResult<A> result) {
        public Cancellation {
            Objects.requireNonNull(owner, "owner");
            Objects.requireNonNull(result, "result");
        }
    }
}
