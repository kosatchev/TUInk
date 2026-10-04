package dev.tuink.tui.input;

import dev.tuink.tui.command.TuinkCommand;
import dev.tuiframe.input.TextInput;
import dev.tuiframe.input.TuiInputEvent;
import java.util.Objects;

/** Physical input and its resolved command, when the focused UI accepts one. */
public record EditorInput(TuiInputEvent event, TuinkCommand command) {
    public EditorInput {
        if (event == null && command == null) {
            throw new IllegalArgumentException("Input must contain an event or a command");
        }
    }

    public static EditorInput command(TuinkCommand command) {
        return new EditorInput(null, Objects.requireNonNull(command, "command"));
    }

    public static EditorInput text(String text) {
        return new EditorInput(new TextInput(text), null);
    }
}
