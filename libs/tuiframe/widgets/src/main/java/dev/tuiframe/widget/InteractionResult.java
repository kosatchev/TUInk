package dev.tuiframe.widget;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/** Typed, application-independent result of handling one physical input event. */
public record InteractionResult<A>(
    boolean consumed,
    FocusRequest focusRequest,
    Optional<A> output,
    PointerCaptureRequest pointerCaptureRequest
) {
    private static final InteractionResult<?> IGNORED = new InteractionResult<>(
        false,
        FocusRequest.NONE,
        Optional.empty()
    );
    private static final InteractionResult<?> CONSUMED = new InteractionResult<>(
        true,
        FocusRequest.NONE,
        Optional.empty()
    );

    public InteractionResult {
        Objects.requireNonNull(focusRequest, "focusRequest");
        output = Objects.requireNonNull(output, "output");
        Objects.requireNonNull(pointerCaptureRequest, "pointerCaptureRequest");
        if (!consumed && (focusRequest != FocusRequest.NONE || output.isPresent()
            || pointerCaptureRequest != PointerCaptureRequest.NONE)) {
            throw new IllegalArgumentException("an ignored result cannot request effects or produce output");
        }
    }

    public InteractionResult(boolean consumed, FocusRequest focusRequest, Optional<A> output) {
        this(consumed, focusRequest, output, PointerCaptureRequest.NONE);
    }

    public InteractionResult<A> withPointerCapture(PointerCaptureRequest request) {
        PointerCaptureRequest required = Objects.requireNonNull(request, "request");
        return new InteractionResult<>(consumed || required != PointerCaptureRequest.NONE,
            focusRequest, output, required);
    }

    /** Adapts a child's typed output while retaining its independent UI effects. */
    public <B> InteractionResult<B> mapOutput(Function<? super A, ? extends B> mapper) {
        return new InteractionResult<>(consumed, focusRequest,
            output.map(Objects.requireNonNull(mapper, "mapper")), pointerCaptureRequest);
    }

    @SuppressWarnings("unchecked")
    public static <A> InteractionResult<A> ignored() {
        return (InteractionResult<A>) IGNORED;
    }

    @SuppressWarnings("unchecked")
    public static <A> InteractionResult<A> handled() {
        return (InteractionResult<A>) CONSUMED;
    }

    public static <A> InteractionResult<A> output(A output) {
        return new InteractionResult<>(
            true,
            FocusRequest.NONE,
            Optional.of(Objects.requireNonNull(output, "output"))
        );
    }

    public static <A> InteractionResult<A> focus(FocusRequest request) {
        FocusRequest required = Objects.requireNonNull(request, "request");
        if (required == FocusRequest.NONE) {
            return handled();
        }
        return new InteractionResult<>(
            true,
            required,
            Optional.empty()
        );
    }

    /** Combines independent effects and rejects ambiguous competing outputs or focus requests. */
    public InteractionResult<A> merge(InteractionResult<A> other) {
        Objects.requireNonNull(other, "other");
        Optional<A> mergedOutput;
        if (output.isEmpty()) {
            mergedOutput = other.output;
        } else if (other.output.isEmpty()) {
            mergedOutput = output;
        } else {
            throw new IllegalArgumentException("cannot merge more than one widget output");
        }

        FocusRequest mergedFocus;
        if (focusRequest == FocusRequest.NONE) {
            mergedFocus = other.focusRequest;
        } else if (other.focusRequest == FocusRequest.NONE || focusRequest == other.focusRequest) {
            mergedFocus = focusRequest;
        } else {
            throw new IllegalArgumentException("cannot merge different focus requests");
        }
        boolean mergedConsumed = consumed || other.consumed;
        if (!mergedConsumed) {
            return ignored();
        }
        PointerCaptureRequest mergedCapture;
        if (pointerCaptureRequest == PointerCaptureRequest.NONE) {
            mergedCapture = other.pointerCaptureRequest;
        } else if (other.pointerCaptureRequest == PointerCaptureRequest.NONE
            || pointerCaptureRequest == other.pointerCaptureRequest) {
            mergedCapture = pointerCaptureRequest;
        } else {
            throw new IllegalArgumentException("cannot merge different pointer capture requests");
        }
        return new InteractionResult<>(
            true,
            mergedFocus,
            mergedOutput,
            mergedCapture
        );
    }
}
