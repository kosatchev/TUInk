package dev.tuink.terminal.widget;

import dev.tuink.terminal.input.TuiInputEvent;

/** Interactive capability bound to final placement geometry. */
public interface PlacedInteractiveWidget<A> extends PlacedWidget {
    InteractionResult<A> handle(TuiInputEvent event, FocusContext context);
}
