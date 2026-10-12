package dev.tuink.tui;

import dev.tuiframe.widget.TextField;
import dev.tuink.core.EditorClipboard;
import dev.tuink.tui.command.TuinkCommand;

/** Application clipboard adapter; copy and cut operate on the field's local selection. */
final class FieldClipboard {
    private FieldClipboard() {
    }

    static boolean handle(TuinkCommand command, TextField field, EditorClipboard clipboard) {
        if (command == TuinkCommand.EDIT_PASTE) {
            field.paste(clipboard.text());
        } else if (command == TuinkCommand.EDIT_COPY || command == TuinkCommand.EDIT_CUT) {
            if (field.hasSelection()) {
                clipboard.setText(field.selectedText());
                if (command == TuinkCommand.EDIT_CUT) {
                    field.deleteSelection();
                }
            }
        } else {
            return false;
        }
        return true;
    }
}
