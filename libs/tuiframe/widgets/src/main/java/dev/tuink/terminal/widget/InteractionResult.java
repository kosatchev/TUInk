package dev.tuink.terminal.widget;

import java.util.Objects;
import java.util.Optional;

/** Typed, application-independent result of handling one physical input event. */
public record InteractionResult<A>(
    boolean consumed,
    FocusRequest focusRequest,
    Optional<A> output
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
        if (!consumed && (focusRequest != FocusRequest.NONE || output.isPresent())) {
            throw new IllegalArgumentException("an ignored result cannot request effects or produce output");
        }
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
        return new InteractionResult<>(
            true,
            mergedFocus,
            mergedOutput
        );
    }
}
