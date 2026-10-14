package dev.tuink.tui;

import dev.tuiframe.cell.Canvas;
import dev.tuiframe.geometry.Rect;
import dev.tuiframe.input.PointerEvent;
import dev.tuiframe.input.TuiInputEvent;
import dev.tuiframe.widget.FocusContext;
import dev.tuiframe.widget.FocusManager;
import dev.tuiframe.widget.FocusRequest;
import dev.tuiframe.widget.FocusScope;
import dev.tuiframe.widget.InteractionResult;
import dev.tuiframe.widget.PlacedInteractiveWidget;
import dev.tuiframe.widget.PlacedPointerCapture;
import dev.tuiframe.widget.PointerCaptureCancellation;
import dev.tuiframe.widget.PointerCaptureManager;
import dev.tuiframe.widget.PointerCaptureRequest;
import dev.tuiframe.widget.RoutedTarget;
import dev.tuiframe.widget.Theme;
import dev.tuiframe.widget.WidgetState;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Adapts the window's typed control scopes to one TUIframe capture owner.
 * Build a fresh {@link Frame} from current placements for each input delivery.
 * Only the TUIframe manager survives frames; no placement or adopter is stored
 * by this coordinator.
 */
final class WindowPointerCapture {
    private final PointerCaptureManager<Object> manager = new PointerCaptureManager<>();

    static Frame frame(Object surfaceId) {
        return new Frame(surfaceId);
    }

    boolean active() {
        return manager.captured().isPresent();
    }

    boolean beforeInput(Frame frame, TuiInputEvent event) {
        Frame required = Objects.requireNonNull(frame, "frame");
        var cancellation = manager.beforeInput(required.surfaceId, required.scopes, event);
        cancellation.ifPresent(WindowPointerCapture::adoptCancellation);
        return cancellation.isPresent();
    }

    boolean cancel(Frame frame, PointerCaptureCancellation reason) {
        Frame required = Objects.requireNonNull(frame, "frame");
        var cancellation = manager.cancel(required.surfaceId, required.scopes, reason);
        cancellation.ifPresent(WindowPointerCapture::adoptCancellation);
        return cancellation.isPresent();
    }

    /** Delivers drag/release before modal, menu and ordinary hit routing. */
    boolean dispatch(Frame frame, PointerEvent event) {
        Frame required = Objects.requireNonNull(frame, "frame");
        PointerCaptureManager.Target<Object, Runnable> owner = manager.route(
            required.surfaceId, required.scopes, event).orElse(null);
        if (owner == null) {
            return false;
        }
        RoutedTarget<Object, Runnable> target = owner.target();
        InteractionResult<Runnable> result = target.placement().handle(event,
            new FocusContext(false, target.enabled(), target.space()));
        result.output().ifPresent(Runnable::run);
        manager.apply(required.surfaceId, owner.scopeId(), target, result);
        manager.reconcile(required.surfaceId, required.scopes)
            .ifPresent(WindowPointerCapture::adoptCancellation);
        return true;
    }

    /** Records a normal route's capture effect without changing its typed output. */
    <K, A> void observe(Object surfaceId, FocusScope<K, A> scope, K targetId, InteractionResult<A> result) {
        if (result.pointerCaptureRequest() == PointerCaptureRequest.NONE) {
            return;
        }
        RoutedTarget<K, A> target = scope.target(targetId).orElseThrow();
        RoutedTarget<Object, A> adapted = new RoutedTarget<>(target.id(), target.placement(), target.space(),
            target.focusable(), target.enabled());
        manager.apply(surfaceId, scope.id(), adapted, result);
    }

    private static void adoptCancellation(PointerCaptureManager.Cancellation<Object, Runnable> cancellation) {
        cancellation.result().output().ifPresent(Runnable::run);
    }

    /** Current-input bindings and adopters; never retained by WindowPointerCapture. */
    static final class Frame {
        private final Object surfaceId;
        private final List<FocusScope<Object, Runnable>> scopes = new ArrayList<>();

        private Frame(Object surfaceId) {
            this.surfaceId = Objects.requireNonNull(surfaceId, "surfaceId");
        }

        <K, A> Frame addScope(FocusScope<K, A> scope, FocusManager<K> focusManager, Consumer<A> adopt) {
            Objects.requireNonNull(focusManager, "focusManager");
            return addBindings(scope, focusManager, adopt);
        }

        /** Pointer-only bindings, such as document or scrollbar adapters. */
        <K, A> Frame addScope(FocusScope<K, A> scope, Consumer<A> adopt) {
            return addBindings(scope, null, adopt);
        }

        private <K, A> Frame addBindings(FocusScope<K, A> scope, FocusManager<K> focusManager, Consumer<A> adopt) {
            Objects.requireNonNull(scope, "scope");
            Objects.requireNonNull(adopt, "adopt");
            List<RoutedTarget<Object, Runnable>> targets = new ArrayList<>();
            for (RoutedTarget<K, A> target : scope.targets()) {
                Adapter<K, A> adapter = target.placement() instanceof PlacedPointerCapture<?>
                    ? new CapturingAdapter<>(scope, target, focusManager, adopt)
                    : new Adapter<>(scope, target, focusManager, adopt);
                targets.add(new RoutedTarget<>(target.id(), adapter, target.space(),
                    target.focusable(), target.enabled()));
            }
            scopes.add(new FocusScope<>(scope.id(), scope.resetPolicy(), targets,
                scope.tabOrder().stream().map(id -> (Object) id).toList(),
                scope.pointerTopmostFirst().stream().map(id -> (Object) id).toList()));
            return this;
        }
    }

    private static class Adapter<K, A> implements PlacedInteractiveWidget<Runnable> {
        private final FocusScope<K, A> scope;
        private final RoutedTarget<K, A> target;
        private final FocusManager<K> focus;
        private final Consumer<A> adopt;

        private Adapter(FocusScope<K, A> scope, RoutedTarget<K, A> target,
            FocusManager<K> focus, Consumer<A> adopt) {
            this.scope = scope;
            this.target = target;
            this.focus = focus;
            this.adopt = adopt;
        }

        @Override
        public Rect bounds() {
            return target.placement().bounds();
        }

        @Override
        public void paint(Canvas canvas, Theme theme, WidgetState state) {
            target.placement().paint(canvas, theme, state);
        }

        @Override
        public InteractionResult<Runnable> handle(TuiInputEvent event, FocusContext ignored) {
            boolean focused = focus != null && target.enabled()
                && focus.activeScopeId().filter(scope.id()::equals).isPresent()
                && focus.focused().filter(target.id()::equals).isPresent();
            InteractionResult<A> result = target.placement().handle(event,
                new FocusContext(focused, target.enabled(), target.space()));
            if (focus != null && result.focusRequest() != FocusRequest.NONE) {
                focus.apply(scope, target.id(), result.focusRequest());
            }
            return mapped(result);
        }

        final InteractionResult<Runnable> mapped(InteractionResult<A> result) {
            return result.mapOutput(action -> () -> adopt.accept(action));
        }
    }

    private static final class CapturingAdapter<K, A> extends Adapter<K, A>
        implements PlacedPointerCapture<Runnable> {
        private final PlacedPointerCapture<A> capture;

        @SuppressWarnings("unchecked")
        private CapturingAdapter(FocusScope<K, A> scope, RoutedTarget<K, A> target,
            FocusManager<K> focus, Consumer<A> adopt) {
            super(scope, target, focus, adopt);
            capture = (PlacedPointerCapture<A>) target.placement();
        }

        @Override
        public boolean pointerCaptureActive() {
            return capture.pointerCaptureActive();
        }

        @Override
        public InteractionResult<Runnable> cancelPointerCapture(PointerCaptureCancellation reason) {
            return mapped(capture.cancelPointerCapture(reason));
        }
    }
}
