package dev.tuiframe.widget;

import dev.tuiframe.input.TuiInputEvent;

/**
 * Interactive capability bound to final placement geometry. A placement may
 * additionally implement {@link PlacedPointerCapture} for gestures delivered
 * beyond its bounds by {@link PointerCaptureManager}.
 */
public interface PlacedInteractiveWidget<A> extends PlacedWidget {
    InteractionResult<A> handle(TuiInputEvent event, FocusContext context);
}
