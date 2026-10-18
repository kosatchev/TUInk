package dev.tuink.tui;

import dev.tuiframe.widget.TextField;
import dev.tuink.core.EditorClipboard;
import dev.tuink.tui.command.TuinkCommand;

/** Application clipboard adapter; copy and cut operate on the field's local selection. */
final class FieldClipboard {
    private FieldClipboard() {
    }

    enum Result {
        IGNORED, HANDLED, INPUT_REJECTED;

        boolean handled() { return this != IGNORED; }
    }

    static Result handle(TuinkCommand command, TextField field, EditorClipboard clipboard) {
        if (command == TuinkCommand.EDIT_PASTE) {
            return field.paste(clipboard.text()) ? Result.HANDLED : Result.INPUT_REJECTED;
        } else if (command == TuinkCommand.EDIT_COPY || command == TuinkCommand.EDIT_CUT) {
            if (field.hasSelection()) {
                String selected;
                try {
                    selected = field.selectedText();
                } catch (OutOfMemoryError exhausted) {
                    return Result.INPUT_REJECTED;
                }
                if (command == TuinkCommand.EDIT_CUT && !field.deleteSelection()) return Result.INPUT_REJECTED;
                // Both the copied string and any deletion are prepared before replacing
                // the old clipboard. Publishing this String reference cannot allocate.
                clipboard.setText(selected);
            }
        } else {
            return Result.IGNORED;
        }
        return Result.HANDLED;
    }
}
