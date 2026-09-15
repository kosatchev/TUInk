package dev.tuiframe.widget;

import dev.tuiframe.input.TuiInputEvent;

/** Interactive capability bound to final placement geometry. */
public interface PlacedInteractiveWidget<A> extends PlacedWidget {
    InteractionResult<A> handle(TuiInputEvent event, FocusContext context);
}
