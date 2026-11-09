package dev.tuiframe.widget;

import dev.tuiframe.cell.Canvas;
import dev.tuiframe.geometry.Rect;

/** A control whose popup paints after sibling content and routes input before its containing surface. */
public interface PlacedPopup<A> extends PlacedInteractiveWidget<A> {
    boolean popupOpen();
    Rect popupBounds();
    void dismissPopup();
    void paintPopup(Canvas canvas, Theme theme, WidgetState state);
}
