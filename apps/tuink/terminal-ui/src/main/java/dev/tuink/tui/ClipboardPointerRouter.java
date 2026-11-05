package dev.tuink.tui;

import dev.tuiframe.input.PointerEvent;
import dev.tuiframe.widget.FocusScope;
import dev.tuiframe.widget.TextField;
import java.util.Optional;
import java.util.function.Function;

/** Resolves an editable text recipient from the same clipped placements as normal pointer input. */
final class ClipboardPointerRouter {
    private ClipboardPointerRouter() {}

    record FieldTarget<K>(K id, TextField field) {}

    static <K, A> Optional<FieldTarget<K>> fieldAt(FocusScope<K, A> scope, PointerEvent pointer,
        Function<K, Optional<TextField>> fields) {
        for (K id : scope.pointerTopmostFirst()) {
            var target = scope.target(id).orElseThrow();
            var point = target.space().localPoint(pointer);
            if (point.isEmpty() || !target.placement().hit(point.get())) continue;
            // The topmost hit owns the location even when it is disabled or is a button.
            if (!target.focusEligible()) return Optional.empty();
            return fields.apply(id).map(field -> new FieldTarget<>(id, field));
        }
        return Optional.empty();
    }
}
