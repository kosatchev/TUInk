package dev.tuink.tui;

import static org.junit.jupiter.api.Assertions.*;

import dev.tuink.core.EditorClipboard;
import dev.tuink.tui.command.TuinkCommand;
import dev.tuiframe.input.PasteEvent;
import dev.tuiframe.input.TextInput;
import dev.tuiframe.widget.TextField;
import dev.tuiframe.widget.FocusContext;
import dev.tuiframe.widget.FocusResetPolicy;
import dev.tuiframe.widget.FocusScope;
import dev.tuiframe.widget.FocusSpace;
import dev.tuiframe.widget.RoutedTarget;
import dev.tuiframe.geometry.Rect;
import org.junit.jupiter.api.Test;

class FieldInputDispatcherTest {
    @Test void disabledOrUnfocusedRecipientsCannotEditThroughClipboardOrText() {
        var recipient = UiRecipient.window(new TabUiState(), null);
        var clipboard = new EditorClipboard(); clipboard.setText("replacement");
        for (boolean enabled : new boolean[] {false, true}) {
            var field = new TextField(); field.set("original"); field.selectAll();
            var bounds = new Rect(0, 0, 20, 1);
            var scope = FocusScope.single("scope", FocusResetPolicy.CLEARED,
                new RoutedTarget<>("field", field.layout(bounds), FocusSpace.root(bounds), true, enabled));
            var context = new FocusContext(false, enabled, FocusSpace.root(bounds));
            for (var input : java.util.List.of(WindowInput.command(new CommandRouter(), TuinkCommand.EDIT_PASTE, recipient),
                new WindowInput.Text("typed", false, false, recipient))) {
                assertTrue(FieldInputDispatcher.input(input, field, clipboard, scope, "field", context, null,
                    "window", FieldInputDispatcher.MEMORY_BOUNDED).consumed());
                assertEquals("original", field.selectedText());
                assertEquals("replacement", clipboard.text());
            }
        }
    }

    @Test void admissionIsAnOwnerPolicyAndDoesNotApplyPreviewLimitToSourceFields() {
        var source = new TextField();
        var preview = new TextField();
        var clipboard = new EditorClipboard();
        clipboard.setText("x".repeat(5000));
        var accepted = assertInstanceOf(FieldInputDispatcher.Applied.class,
            FieldInputDispatcher.command(TuinkCommand.EDIT_PASTE, source, clipboard, FieldInputDispatcher.MEMORY_BOUNDED));
        assertTrue(accepted.valueChanged()); assertFalse(accepted.inputRejected());
        assertEquals(5000, source.length());
        preview.set("alpha 😀"); preview.selectAll();
        var rejected = assertInstanceOf(FieldInputDispatcher.Applied.class,
            FieldInputDispatcher.command(TuinkCommand.EDIT_PASTE, preview, clipboard, MarkdownPreviewState.SEARCH_QUERY_MAX_UTF16));
        assertTrue(rejected.inputRejected()); assertFalse(rejected.valueChanged());
        assertEquals("alpha 😀", preview.selectedText());
        assertEquals(5000, clipboard.text().length());
    }

    @Test void rejectedRawInsertionNeverReachesTheWidgetAndKeepsItsSelection() {
        var field = new TextField(); field.set("selected"); field.selectAll();
        for (var input : java.util.List.of(new TextInput("x".repeat(4097)), new PasteEvent("partial", true))) {
            var result = FieldInputDispatcher.event(field, input, MarkdownPreviewState.SEARCH_QUERY_MAX_UTF16,
                ignored -> { fail("Rejected insertion reached the widget"); return null; }, TextField.Action.INPUT_REJECTED);
            assertEquals(TextField.Action.INPUT_REJECTED, result.output().orElseThrow());
            assertEquals("selected", field.selectedText());
        }
    }
}
