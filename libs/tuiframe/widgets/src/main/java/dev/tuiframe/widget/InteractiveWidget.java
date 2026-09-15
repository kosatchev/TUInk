package dev.tuiframe.widget;

import dev.tuiframe.geometry.Rect;

/**
 * Optional interactive capability for a {@link Widget}. The typed output is
 * owned by the consumer; reusable widgets never expose application commands.
 *
 * @param <A> semantic output produced by the widget
 */
public interface InteractiveWidget<A> extends Widget {
    @Override
    PlacedInteractiveWidget<A> layout(Rect bounds);
}
