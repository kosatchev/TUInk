package dev.tuiframe.widget;

import dev.tuiframe.input.TuiInputEvent;
import java.util.function.Function;

/**
 * Interactive capability bound to final placement geometry. A placement may
 * additionally implement {@link PlacedPointerCapture} for gestures delivered
 * beyond its bounds by {@link PointerCaptureManager}.
 */
public interface PlacedInteractiveWidget<A> extends PlacedWidget {
    /**
     * Whether this allocation can represent the widget's interactive content.
     * Parent clipping and authored enabled state are checked by the routing target.
     * Output-mapping adapters must forward this capability to their delegate.
     */
    default boolean interactionReady() {
        return !bounds().isEmpty();
    }

    /**
     * Maps typed output while preserving this placement's geometry, readiness,
     * paint and independent interaction effects. The result exposes capture and
     * cursor and popup capabilities exactly when this placement exposes them.
     */
    default <B> PlacedInteractiveWidget<B> mapOutput(Function<? super A, ? extends B> mapper) {
        return OutputMappedPlacement.create(this, mapper);
    }

    InteractionResult<A> handle(TuiInputEvent event, FocusContext context);
}
