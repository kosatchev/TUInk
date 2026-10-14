package dev.tuiframe.widget;

/**
 * Optional pointer-gesture capability. The widget owns gesture data; the
 * capture manager owns delivery. Cancellation returns ordinary widget output,
 * so immutable state is adopted in exactly the same way as input output.
 */
public interface PlacedPointerCapture<A> extends PlacedInteractiveWidget<A> {
    boolean pointerCaptureActive();

    InteractionResult<A> cancelPointerCapture(PointerCaptureCancellation reason);
}
