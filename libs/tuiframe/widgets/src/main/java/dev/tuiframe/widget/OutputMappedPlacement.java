package dev.tuiframe.widget;

import dev.tuiframe.cell.Canvas;
import dev.tuiframe.geometry.Point;
import dev.tuiframe.geometry.Rect;
import dev.tuiframe.input.TuiInputEvent;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/** Capability-preserving implementation of the placed-widget output mapper. */
final class OutputMappedPlacement {
    private OutputMappedPlacement() {
    }

    @SuppressWarnings("unchecked")
    static <A, B> PlacedInteractiveWidget<B> create(
        PlacedInteractiveWidget<A> delegate, Function<? super A, ? extends B> mapper
    ) {
        Objects.requireNonNull(delegate, "delegate");
        Objects.requireNonNull(mapper, "mapper");
        if (delegate instanceof PlacedPointerCapture<?> capture) {
            PlacedPointerCapture<A> typedCapture = (PlacedPointerCapture<A>) capture;
            return delegate instanceof PlacedCursorSource cursor
                ? new CaptureCursorMapping<>(typedCapture, cursor, mapper)
                : new CaptureMapping<>(typedCapture, mapper);
        }
        return delegate instanceof PlacedCursorSource cursor
            ? new CursorMapping<>(delegate, cursor, mapper)
            : new Mapping<>(delegate, mapper);
    }

    private static class Mapping<A, B> implements PlacedInteractiveWidget<B> {
        private final PlacedInteractiveWidget<A> delegate;
        private final Function<? super A, ? extends B> mapper;

        Mapping(PlacedInteractiveWidget<A> delegate, Function<? super A, ? extends B> mapper) {
            this.delegate = delegate;
            this.mapper = mapper;
        }

        @Override
        public Rect bounds() {
            return delegate.bounds();
        }

        @Override
        public boolean hit(int row, int column) {
            return delegate.hit(row, column);
        }

        @Override
        public boolean hit(Point point) {
            return delegate.hit(point);
        }

        @Override
        public boolean interactionReady() {
            return delegate.interactionReady();
        }

        @Override
        public void paint(Canvas canvas, Theme theme, WidgetState state) {
            delegate.paint(canvas, theme, state);
        }

        @Override
        public InteractionResult<B> handle(TuiInputEvent event, FocusContext context) {
            return mapped(delegate.handle(event, context));
        }

        final InteractionResult<B> mapped(InteractionResult<A> result) {
            return result.mapOutput(mapper);
        }
    }

    private static class CaptureMapping<A, B> extends Mapping<A, B> implements PlacedPointerCapture<B> {
        private final PlacedPointerCapture<A> capture;

        CaptureMapping(PlacedPointerCapture<A> capture, Function<? super A, ? extends B> mapper) {
            super(capture, mapper);
            this.capture = capture;
        }

        @Override
        public boolean pointerCaptureActive() {
            return capture.pointerCaptureActive();
        }

        @Override
        public InteractionResult<B> cancelPointerCapture(PointerCaptureCancellation reason) {
            return mapped(capture.cancelPointerCapture(reason));
        }
    }

    private static final class CursorMapping<A, B> extends Mapping<A, B> implements PlacedCursorSource {
        private final PlacedCursorSource cursor;

        CursorMapping(PlacedInteractiveWidget<A> delegate, PlacedCursorSource cursor,
            Function<? super A, ? extends B> mapper) {
            super(delegate, mapper);
            this.cursor = cursor;
        }

        @Override
        public Optional<Point> cursorPosition(FocusContext context) {
            return cursor.cursorPosition(context);
        }
    }

    private static final class CaptureCursorMapping<A, B> extends CaptureMapping<A, B> implements PlacedCursorSource {
        private final PlacedCursorSource cursor;

        CaptureCursorMapping(PlacedPointerCapture<A> capture, PlacedCursorSource cursor,
            Function<? super A, ? extends B> mapper) {
            super(capture, mapper);
            this.cursor = cursor;
        }

        @Override
        public Optional<Point> cursorPosition(FocusContext context) {
            return cursor.cursorPosition(context);
        }
    }
}
