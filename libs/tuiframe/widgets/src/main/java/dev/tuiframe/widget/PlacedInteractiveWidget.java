package dev.tuiframe.widget;

import dev.tuiframe.input.TuiInputEvent;

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

    InteractionResult<A> handle(TuiInputEvent event, FocusContext context);
}
