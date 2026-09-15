package dev.tuiframe.widget;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tuiframe.geometry.Rect;
import dev.tuiframe.widget.DialogInputPolicy.Action;
import dev.tuiframe.widget.DialogInputPolicy.Decision;
import dev.tuiframe.widget.DialogInputPolicy.Target;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class DialogInputPolicyTest {
    @Test
    void modesPermittingContentFocusAllowReadingSelectionNavigationAndCopying() {
        for (DialogMode mode : List.of(DialogMode.BLOCKING, DialogMode.NON_BLOCKING)) {
            for (Action action : List.of(Action.READ, Action.COPY, Action.OTHER)) {
                Decision decision = DialogInputPolicy.decide(mode, Target.CONTENT, action);
                assertEquals(Decision.ALLOW, decision, mode + " " + action);
                assertFalse(decision.consumed(), "allowed content input must reach its owner");
            }
        }
    }

    @Test
    void exclusiveModeDisallowsContentFocusAndBlocksEveryContentAction() {
        for (DialogMode mode : DialogMode.values()) {
            assertEquals(mode != DialogMode.EXCLUSIVE, mode.permitsContentFocus(), mode.toString());
        }
        for (Action action : Action.values()) {
            Decision decision = DialogInputPolicy.decide(DialogMode.EXCLUSIVE, Target.CONTENT, action);
            assertEquals(Decision.BLOCK, decision, action.toString());
            assertTrue(decision.consumed(), "exclusive dialogs must capture even content reading and copying");
        }
    }

    @Test
    void blockingContentRejectsEveryMutationWhileNonBlockingContentCanEdit() {
        for (Action action : List.of(Action.MODIFY, Action.CUT, Action.PASTE, Action.UNDO, Action.REDO)) {
            Decision blocking = DialogInputPolicy.decide(DialogMode.BLOCKING, Target.CONTENT, action);
            assertEquals(Decision.BLOCK, blocking, action.toString());
            assertTrue(blocking.consumed(), "rejected editing must not be replayed against the content");

            Decision nonBlocking = DialogInputPolicy.decide(DialogMode.NON_BLOCKING, Target.CONTENT, action);
            assertEquals(Decision.ALLOW, nonBlocking, action.toString());
            assertFalse(nonBlocking.consumed());
        }
    }

    @Test
    void buttonClipboardShortcutsAreConsumedQuietlyInAllModes() {
        for (DialogMode mode : DialogMode.values()) {
            for (Action action : List.of(Action.COPY, Action.CUT, Action.PASTE)) {
                Decision decision = DialogInputPolicy.decide(mode, Target.BUTTON, action);
                assertEquals(Decision.IGNORE, decision, mode + " " + action);
                assertTrue(decision.consumed());
                List<String> effects = new ArrayList<>();
                DialogInputPolicy.apply(decision, () -> effects.add("focus"), () -> effects.add("attention"));
                assertEquals(List.of(), effects, "clipboard commands on buttons must not move focus or flash");
            }
        }
    }

    @Test
    void dialogTextFieldsKeepTheirOwnEditingAndClipboardInAllModes() {
        for (DialogMode mode : DialogMode.values()) {
            for (Action action : List.of(Action.READ, Action.MODIFY, Action.COPY, Action.CUT, Action.PASTE)) {
                Decision decision = DialogInputPolicy.decide(mode, Target.TEXT_INPUT, action);
                assertEquals(Decision.ALLOW, decision, mode + " " + action);
                assertFalse(decision.consumed());
            }
        }
    }

    @Test
    void exclusiveModeKeepsEveryControlDecisionEqualToOtherModes() {
        for (Target target : List.of(Target.TEXT_INPUT, Target.BUTTON, Target.OTHER_CONTROL)) {
            for (Action action : Action.values()) {
                Decision expected = DialogInputPolicy.decide(DialogMode.BLOCKING, target, action);
                for (DialogMode mode : DialogMode.values()) {
                    assertEquals(expected, DialogInputPolicy.decide(mode, target, action),
                        mode + " " + target + " " + action);
                }
            }
        }
    }

    @Test
    void historyCommandsOnDialogControlsNeverReachTheContentHistory() {
        for (DialogMode mode : DialogMode.values()) {
            for (Target target : List.of(Target.TEXT_INPUT, Target.BUTTON, Target.OTHER_CONTROL)) {
                for (Action action : List.of(Action.UNDO, Action.REDO)) {
                    Decision decision = DialogInputPolicy.decide(mode, target, action);
                    assertEquals(Decision.IGNORE, decision, mode + " " + target + " " + action);
                    assertTrue(decision.consumed());
                }
            }
        }
    }

    @Test
    void blockingRejectionFocusesTheFirstControlThenSignalsAttentionExactlyOnceWithoutEditingContent() {
        FocusManager<String> focus = new FocusManager<>();
        FocusScope<String, Button.Action> scope = scope();
        focus.clear(scope);
        DialogAttention attention = new DialogAttention();
        TextField content = new TextField();
        content.set("original content");
        List<String> effects = new ArrayList<>();
        Decision decision = DialogInputPolicy.decide(DialogMode.BLOCKING, Target.CONTENT, Action.MODIFY);

        DialogInputPolicy.apply(decision, () -> {
            effects.add("focus");
            DialogFocusTraversal.focusFirst(focus, scope);
        }, () -> {
            effects.add("attention");
            assertEquals("first", focus.focused().orElseThrow(), "focus must be restored before attention is signaled");
            assertTrue(attention.request(scope.id(), 0));
        });
        if (!decision.consumed()) {
            content.insert("unexpected input");
        }

        assertEquals(List.of("focus", "attention"), effects);
        assertEquals("first", focus.focused().orElseThrow());
        assertTrue(attention.highlighted());
        assertEquals("original content", content.text());
    }

    @Test
    void allowingAndQuietlyIgnoringInputPreserveFocusAndDoNotRequestAttention() {
        for (Decision decision : List.of(Decision.ALLOW, Decision.IGNORE)) {
            FocusManager<String> focus = new FocusManager<>();
            FocusScope<String, Button.Action> scope = scope();
            focus.focus(scope, "second");
            DialogAttention attention = new DialogAttention();
            List<String> effects = new ArrayList<>();

            DialogInputPolicy.apply(decision, () -> {
                effects.add("focus");
                DialogFocusTraversal.focusFirst(focus, scope);
            }, () -> {
                effects.add("attention");
                attention.request(scope.id(), 0);
            });

            assertEquals(List.of(), effects, decision.toString());
            assertEquals("second", focus.focused().orElseThrow());
            assertFalse(attention.highlighted());
            assertEquals(decision == Decision.IGNORE, decision.consumed());
        }
    }

    private static FocusScope<String, Button.Action> scope() {
        Rect clip = new Rect(0, 0, 30, 4);
        FocusSpace space = FocusSpace.root(clip);
        return new FocusScope<>("dialog", FocusResetPolicy.FIRST_FOCUSABLE, List.of(
            new RoutedTarget<>("first", Button.plain("First").layout(new Rect(1, 1, 5, 1)), space),
            new RoutedTarget<>("second", Button.plain("Second").layout(new Rect(8, 1, 6, 1)), space)
        ), List.of("first", "second"), List.of("first", "second"));
    }
}
