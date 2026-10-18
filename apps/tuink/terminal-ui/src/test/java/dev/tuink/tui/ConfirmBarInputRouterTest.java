package dev.tuink.tui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tuiframe.input.KeyStroke;
import dev.tuiframe.input.PasteEvent;
import dev.tuiframe.input.PointerEvent;
import dev.tuiframe.input.TextInput;
import dev.tuiframe.input.TuiInputEvent;
import dev.tuiframe.geometry.Rect;
import dev.tuiframe.widget.Button;
import dev.tuiframe.widget.FocusManager;
import dev.tuiframe.widget.FocusScope;
import dev.tuiframe.widget.RoutedTarget;
import dev.tuink.tui.flow.ConfirmChoice;
import dev.tuink.tui.ui.ConfirmBarView;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ConfirmBarInputRouterTest {
    @Test
    void threeButtonScopeStartsAtCancelAndUsesExactTabDirection() {
        Fixture fixture = standard(ConfirmBarInputRouter.Policy.quit(), 80);

        assertEquals(ConfirmChoice.CANCEL, fixture.focus.focused().orElseThrow());
        assertCapturedWithoutDecision(fixture.route(KeyStroke.of(KeyStroke.Key.TAB)));
        assertEquals(ConfirmChoice.SAVE, fixture.focus.focused().orElseThrow());
        assertCapturedWithoutDecision(fixture.route(
            KeyStroke.of(KeyStroke.Key.TAB, KeyStroke.Modifier.SHIFT)
        ));
        assertEquals(ConfirmChoice.CANCEL, fixture.focus.focused().orElseThrow());

        assertCapturedWithoutDecision(fixture.route(
            KeyStroke.of(KeyStroke.Key.TAB, KeyStroke.Modifier.CTRL)
        ));
        assertEquals(ConfirmChoice.CANCEL, fixture.focus.focused().orElseThrow());
    }

    @Test
    void arrowsHomeAndEndFollowVisibleRenderedOrder() {
        Fixture fixture = standard(ConfirmBarInputRouter.Policy.quit(), 80);

        assertCapturedWithoutDecision(fixture.route(KeyStroke.of(KeyStroke.Key.ARROW_RIGHT)));
        assertEquals(ConfirmChoice.SAVE, fixture.focus.focused().orElseThrow());
        assertCapturedWithoutDecision(fixture.route(KeyStroke.of(KeyStroke.Key.ARROW_LEFT)));
        assertEquals(ConfirmChoice.CANCEL, fixture.focus.focused().orElseThrow());
        assertCapturedWithoutDecision(fixture.route(KeyStroke.of(KeyStroke.Key.HOME)));
        assertEquals(ConfirmChoice.SAVE, fixture.focus.focused().orElseThrow());
        assertCapturedWithoutDecision(fixture.route(KeyStroke.of(KeyStroke.Key.END)));
        assertEquals(ConfirmChoice.CANCEL, fixture.focus.focused().orElseThrow());
    }

    @Test
    void recoveryHasThreeTargetsAndStartsAtLater() {
        Object scopeId = new Object();
        ConfirmBarView.Placement placement = ConfirmBarView.buildRecovery("Recover?", 80)
            .place(scopeId, 4, viewport(6, 80));
        Fixture fixture = new Fixture(
            new FocusManager<>(),
            placement,
            ConfirmBarInputRouter.Policy.recovery()
        );
        placement.activate(fixture.focus);

        assertEquals(3, placement.focusScope().tabOrder().size());
        assertEquals(ConfirmChoice.CANCEL, fixture.focus.focused().orElseThrow());
        fixture.route(KeyStroke.of(KeyStroke.Key.TAB));
        assertEquals(ConfirmChoice.SAVE, fixture.focus.focused().orElseThrow());
        fixture.route(KeyStroke.of(KeyStroke.Key.TAB));
        assertEquals(ConfirmChoice.DISCARD, fixture.focus.focused().orElseThrow());
        fixture.route(KeyStroke.of(KeyStroke.Key.TAB));
        assertEquals(ConfirmChoice.CANCEL, fixture.focus.focused().orElseThrow());
    }

    @Test
    void enterSpaceAndPointerReturnTheFocusedSemanticChoice() {
        ConfirmBarView view = ConfirmBarView.build(
            "Unsaved",
            80,
            "Save All",
            "Discard All",
            true,
            "Cancel"
        );
        Fixture fixture = fixture(view, ConfirmBarInputRouter.Policy.quit(), 80, new Object());

        fixture.focus.focus(fixture.scope(), ConfirmChoice.SAVE);
        assertEquals(ConfirmChoice.SAVE, fixture.route(KeyStroke.of(KeyStroke.Key.ENTER)).decision());

        fixture.focus.focus(fixture.scope(), ConfirmChoice.DISCARD);
        assertEquals(ConfirmChoice.DISCARD, fixture.route(new TextInput(" ")).decision());

        RoutedTarget<ConfirmChoice, Button.Action> cancel = fixture.scope()
            .target(ConfirmChoice.CANCEL)
            .orElseThrow();
        int row = cancel.placement().bounds().y();
        int col = cancel.placement().bounds().x();
        ConfirmBarInputRouter.Result click = fixture.route(pointer(
            row,
            col,
            PointerEvent.Kind.PRESS,
            PointerEvent.Button.LEFT
        ));
        assertEquals(ConfirmChoice.CANCEL, click.decision());
        assertEquals(ConfirmChoice.CANCEL, fixture.focus.focused().orElseThrow());
    }

    @Test
    void applicationPoliciesSaveOnYesAndCancelOnNoEscapeAndCtrlQ() {
        Fixture quit = standard(ConfirmBarInputRouter.Policy.quit(), 80);
        assertEquals(ConfirmChoice.SAVE, quit.route(new TextInput("Y")).decision());
        assertEquals(ConfirmChoice.CANCEL, quit.route(new TextInput("n")).decision());
        assertEquals(
            ConfirmChoice.CANCEL,
            quit.route(KeyStroke.of(KeyStroke.Key.ESCAPE)).decision()
        );
        assertEquals(
            ConfirmChoice.CANCEL,
            quit.route(KeyStroke.of(KeyStroke.Key.Q, KeyStroke.Modifier.CTRL)).decision()
        );

        Fixture overwrite = standard(ConfirmBarInputRouter.Policy.overwrite(), 80);
        assertEquals(ConfirmChoice.SAVE, overwrite.route(new TextInput("y")).decision());
        assertEquals(ConfirmChoice.CANCEL, overwrite.route(new TextInput("N")).decision());

        Fixture closeTab = standard(ConfirmBarInputRouter.Policy.closeTab(), 80);
        assertEquals(ConfirmChoice.SAVE, closeTab.route(new TextInput("y")).decision());

        Fixture recovery = fixture(
            ConfirmBarView.buildRecovery("Recover?", 80),
            ConfirmBarInputRouter.Policy.recovery(),
            80,
            new Object()
        );
        assertEquals(ConfirmChoice.SAVE, recovery.route(new TextInput("y")).decision());
        assertEquals(ConfirmChoice.CANCEL, recovery.route(new TextInput("n")).decision());
        assertEquals(
            ConfirmChoice.CANCEL,
            recovery.route(KeyStroke.of(KeyStroke.Key.ESCAPE)).decision()
        );
    }

    @Test
    void exactCtrlQKeepsTheIntendedDecisionInAllConfirmationModes() {
        List<Fixture> fixtures = allConfirmationModes();
        List<ConfirmChoice> expected = List.of(
            ConfirmChoice.CANCEL, ConfirmChoice.CANCEL,
            ConfirmChoice.CANCEL, ConfirmChoice.CANCEL
        );

        for (int index = 0; index < fixtures.size(); index++) {
            ConfirmBarInputRouter.Result result = fixtures.get(index).route(
                KeyStroke.of(KeyStroke.Key.Q, KeyStroke.Modifier.CTRL)
            );

            assertTrue(result.consumed());
            assertEquals(expected.get(index), result.decision());
        }
    }

    @Test
    void ctrlQWithAnyAdditionalModifierIsCapturedWithoutDecidingOrChangingFocus() {
        List<Set<KeyStroke.Modifier>> chords = List.of(
            Set.of(KeyStroke.Modifier.CTRL, KeyStroke.Modifier.SHIFT),
            Set.of(KeyStroke.Modifier.CTRL, KeyStroke.Modifier.ALT),
            Set.of(KeyStroke.Modifier.CTRL, KeyStroke.Modifier.SUPER),
            Set.of(KeyStroke.Modifier.CTRL, KeyStroke.Modifier.SHIFT, KeyStroke.Modifier.ALT),
            Set.of(KeyStroke.Modifier.CTRL, KeyStroke.Modifier.SHIFT, KeyStroke.Modifier.SUPER),
            Set.of(KeyStroke.Modifier.CTRL, KeyStroke.Modifier.ALT, KeyStroke.Modifier.SUPER),
            Set.of(
                KeyStroke.Modifier.CTRL, KeyStroke.Modifier.SHIFT,
                KeyStroke.Modifier.ALT, KeyStroke.Modifier.SUPER
            )
        );
        for (Fixture fixture : allConfirmationModes()) {
            ConfirmChoice originalFocus = fixture.focus.focused().orElseThrow();
            for (Set<KeyStroke.Modifier> modifiers : chords) {
                ConfirmBarInputRouter.Result result = fixture.route(new KeyStroke(KeyStroke.Key.Q, modifiers));

                assertCapturedWithoutDecision(result);
                assertEquals(originalFocus, fixture.focus.focused().orElseThrow());
            }
        }
    }

    @Test
    void pointerMissesBubbleOnlyForCloseTabPolicyAndPasteKeepsSanitizerPath() {
        Fixture overwrite = standard(ConfirmBarInputRouter.Policy.overwrite(), 80);

        ConfirmBarInputRouter.Result blockedPress = overwrite.route(pointer(
            0,
            0,
            PointerEvent.Kind.PRESS,
            PointerEvent.Button.LEFT
        ));
        assertCapturedWithoutDecision(blockedPress);

        ConfirmBarInputRouter.Result blockedScroll = overwrite.route(pointer(
            0,
            0,
            PointerEvent.Kind.SCROLL_DOWN,
            PointerEvent.Button.NONE
        ));
        assertCapturedWithoutDecision(blockedScroll);

        Fixture closeTab = standard(ConfirmBarInputRouter.Policy.closeTab(), 80);
        ConfirmBarInputRouter.Result release = closeTab.route(pointer(
            closeTab.target(ConfirmChoice.SAVE).placement().bounds().y(),
            closeTab.target(ConfirmChoice.SAVE).placement().bounds().x(),
            PointerEvent.Kind.RELEASE,
            PointerEvent.Button.LEFT
        ));
        assertFalse(release.consumed());
        assertNull(release.decision());

        ConfirmBarInputRouter.Result closeTabMiss = closeTab.route(pointer(
            0,
            0,
            PointerEvent.Kind.SCROLL_DOWN,
            PointerEvent.Button.NONE
        ));
        assertFalse(closeTabMiss.consumed());
        assertNull(closeTabMiss.decision());

        ConfirmBarInputRouter.Result paste = overwrite.route(new PasteEvent("ignored", false));
        assertFalse(paste.consumed());
        assertNull(paste.decision());

        assertCapturedWithoutDecision(overwrite.route(new TextInput("unrelated")));
    }

    @Test
    void relayoutKeepsFocusAndDeactivationReappliesPromptDefault() {
        Object scopeId = new Object();
        Fixture original = fixture(
            ConfirmBarView.build("Quit?", 80),
            ConfirmBarInputRouter.Policy.quit(),
            80,
            scopeId
        );
        original.focus.focus(original.scope(), ConfirmChoice.DISCARD);

        ConfirmBarView.Placement resized = ConfirmBarView.build("Quit?", 60)
            .place(scopeId, 3, viewport(5, 60));
        Fixture samePrompt = new Fixture(original.focus, resized, original.policy);
        resized.activate(original.focus);
        assertEquals(ConfirmChoice.DISCARD, original.focus.focused().orElseThrow());

        assertTrue(original.focus.deactivate(scopeId));
        Object nextScopeId = new Object();
        ConfirmBarView.Placement reopened = ConfirmBarView.build("Quit?", 60)
            .place(nextScopeId, 3, viewport(5, 60));
        reopened.activate(original.focus);
        assertEquals(ConfirmChoice.CANCEL, original.focus.focused().orElseThrow());

        ConfirmBarView.Placement recovery = ConfirmBarView.buildRecovery("Recover?", 60)
            .place(new Object(), 3, viewport(5, 60));
        recovery.activate(original.focus);
        assertEquals(ConfirmChoice.CANCEL, original.focus.focused().orElseThrow());
        assertEquals(ConfirmChoice.CANCEL, samePrompt.route(new TextInput("n")).decision());
    }

    @Test
    void hiddenInitialButtonLeavesFocusClearUntilAnExplicitChoice() {
        Fixture fixture = standard(ConfirmBarInputRouter.Policy.quit(), 1);

        assertTrue(fixture.focus.focused().isEmpty());
        assertFalse(fixture.target(ConfirmChoice.SAVE).focusable());
        assertFalse(fixture.target(ConfirmChoice.CANCEL).focusable());
        assertCapturedWithoutDecision(fixture.route(KeyStroke.of(KeyStroke.Key.ENTER)));
        fixture.route(KeyStroke.of(KeyStroke.Key.END));
        assertTrue(fixture.focus.focused().isEmpty());
    }

    @Test
    void visibleSaveDoesNotReplaceAClippedSafeDefault() {
        Fixture fixture = standard(ConfirmBarInputRouter.Policy.quit(), 6);

        assertTrue(fixture.target(ConfirmChoice.SAVE).focusable());
        assertFalse(fixture.target(ConfirmChoice.CANCEL).focusable());
        assertTrue(fixture.focus.focused().isEmpty());
        assertCapturedWithoutDecision(fixture.route(KeyStroke.of(KeyStroke.Key.ENTER)));

        fixture.route(KeyStroke.of(KeyStroke.Key.TAB));
        assertEquals(ConfirmChoice.SAVE, fixture.focus.focused().orElseThrow());
    }

    @Test
    void resizeClearsASelectionThatBecomesHiddenInsteadOfFallingBack() {
        Object scopeId = new Object();
        Fixture wide = fixture(
            ConfirmBarView.build("Quit?", 80),
            ConfirmBarInputRouter.Policy.quit(),
            80,
            scopeId
        );
        assertEquals(ConfirmChoice.CANCEL, wide.focus.focused().orElseThrow());

        ConfirmBarView.Placement narrow = ConfirmBarView.build("Quit?", 6)
            .place(scopeId, 4, viewport(6, 6));
        narrow.activate(wide.focus);

        assertTrue(wide.focus.focused().isEmpty());
        assertTrue(narrow.focusScope().target(ConfirmChoice.SAVE).orElseThrow().focusable());
    }

    private static Fixture standard(ConfirmBarInputRouter.Policy policy, int cols) {
        return fixture(ConfirmBarView.build("Confirm?", cols), policy, cols, new Object());
    }

    private static List<Fixture> allConfirmationModes() {
        return List.of(
            fixture(
                ConfirmBarView.buildRecovery("Recover?", 80),
                ConfirmBarInputRouter.Policy.recovery(),
                80,
                new Object()
            ),
            standard(ConfirmBarInputRouter.Policy.overwrite(), 80),
            standard(ConfirmBarInputRouter.Policy.quit(), 80),
            standard(ConfirmBarInputRouter.Policy.closeTab(), 80)
        );
    }

    private static Fixture fixture(
        ConfirmBarView view,
        ConfirmBarInputRouter.Policy policy,
        int cols,
        Object scopeId
    ) {
        ConfirmBarView.Placement placement = view.place(scopeId, 4, viewport(6, cols));
        FocusManager<ConfirmChoice> focus = new FocusManager<>();
        placement.activate(focus);
        return new Fixture(focus, placement, policy);
    }

    private static Rect viewport(int rows, int cols) {
        return new Rect(0, 0, cols, rows);
    }

    private static PointerEvent pointer(
        int row,
        int col,
        PointerEvent.Kind kind,
        PointerEvent.Button button
    ) {
        return new PointerEvent(row, col, kind, button, Set.of());
    }

    private static void assertCapturedWithoutDecision(ConfirmBarInputRouter.Result result) {
        assertTrue(result.consumed());
        assertNull(result.decision());
    }

    private record Fixture(
        FocusManager<ConfirmChoice> focus,
        ConfirmBarView.Placement placement,
        ConfirmBarInputRouter.Policy policy
    ) {
        private ConfirmBarInputRouter.Result route(TuiInputEvent event) {
            return ConfirmBarInputRouter.route(focus, placement, policy, event);
        }

        private FocusScope<ConfirmChoice, Button.Action> scope() {
            return placement.focusScope();
        }

        private RoutedTarget<ConfirmChoice, Button.Action> target(ConfirmChoice choice) {
            return scope().target(choice).orElseThrow();
        }
    }
}
