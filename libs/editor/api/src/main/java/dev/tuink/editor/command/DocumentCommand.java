package dev.tuink.editor.command;

import dev.tuink.editor.document.TextPosition;
import dev.tuink.editor.document.Utf16Text;
import dev.tuink.editor.search.SearchOptions;
import dev.tuink.editor.format.PreparedFormatChange;
import java.util.Objects;

/** Built-in document operations, independent of keyboard, window and widget commands. */
public sealed interface DocumentCommand {
    /** Session-owner admission of an already validated, unchanged document format. */
    record SetDocumentFormat(PreparedFormatChange prepared, PreparedFormatChange.Validation validation)
        implements DocumentCommand {
        public SetDocumentFormat {
            Objects.requireNonNull(prepared, "prepared");
            Objects.requireNonNull(validation, "validation");
        }
    }
    record Input(String text) implements DocumentCommand {
        public Input { Utf16Text.requireWellFormed(Objects.requireNonNull(text, "text"), "Input"); }
    }
    record Paste(String text) implements DocumentCommand {
        public Paste { Utf16Text.requireWellFormed(Objects.requireNonNull(text, "text"), "Paste"); }
    }
    record Edit(EditAction action) implements DocumentCommand {
        public Edit { Objects.requireNonNull(action, "action"); }
    }
    record Navigate(Navigation action, boolean selecting) implements DocumentCommand {
        public Navigate { Objects.requireNonNull(action, "action"); }
    }
    /** A frontend-resolved navigation target; distinct from exact pointer selection gestures. */
    record MoveTo(TextPosition position, boolean selecting) implements DocumentCommand {
        public MoveTo { Objects.requireNonNull(position, "position"); }
    }
    record Select(SelectionAction action, TextPosition cursor, TextPosition anchor) implements DocumentCommand {
        public Select {
            Objects.requireNonNull(action, "action");
            if (action != SelectionAction.FINISH) Objects.requireNonNull(cursor, "cursor");
        }
    }
    record Search(SearchAction action, String query, String replacement, SearchOptions options)
        implements DocumentCommand {
        public Search {
            Objects.requireNonNull(action, "action");
            Objects.requireNonNull(query, "query");
            Objects.requireNonNull(options, "options");
            replacement = replacement == null ? "" : replacement;
            Utf16Text.requireWellFormed(query, "Query");
            Utf16Text.requireWellFormed(replacement, "Replacement");
        }
    }
    enum EditAction {
        INSERT_TAB, NEWLINE, BACKSPACE, DELETE_FORWARD, COPY, CUT, PASTE,
        SELECT_ALL, UNDO, REDO, TOGGLE_LINE_COMMENT
    }
    enum Navigation {
        LEFT, RIGHT, WORD_LEFT, WORD_RIGHT, HOME, END, HOME_SMART, END_SMART,
        DOCUMENT_START, DOCUMENT_END
    }
    enum SelectionAction { MOVE, BEGIN, EXTEND, RESTORE, FINISH, WORD }
    enum SearchAction { NEXT, PREVIOUS, REPLACE, REPLACE_ALL }
}
