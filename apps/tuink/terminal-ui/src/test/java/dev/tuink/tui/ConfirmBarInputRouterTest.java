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
import dev.tuink.tui.flow.ConfirmationAction;
import dev.tuink.tui.flow.ConfirmationShortcuts;
import dev.tuink.tui.ui.ConfirmBarView;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ConfirmBarInputRouterTest {
    @Test
    void exactCtrlSSavesWritingConfirmationsButDoesNotRestoreRecoveryOrChooseAnEncoding() {
        for (var policy : List.of(ConfirmBarInputRouter.Policy.quit(), ConfirmBarInputRouter.Policy.closeTab(),
            ConfirmBarInputRouter.Policy.overwrite())) {
            var fixture = standard(policy, 80);
            assertEquals(ConfirmChoice.SAVE,
                fixture.route(KeyStroke.of(KeyStroke.Key.S, KeyStroke.Modifier.CTRL)).decision());
            assertCapturedWithoutDecision(fixture.route(KeyStroke.of(KeyStroke.Key.S,
                KeyStroke.Modifier.CTRL, KeyStroke.Modifier.SHIFT)));
        }
        for (var policy : List.of(ConfirmBarInputRouter.Policy.recovery(), ConfirmBarInputRouter.Policy.open())) {
            assertCapturedWithoutDecision(standard(policy, 80).route(KeyStroke.of(KeyStroke.Key.S, KeyStroke.Modifier.CTRL)));
        }
        assertCapturedWithoutDecision(standard(ConfirmBarInputRouter.Policy.quit(), 5)
            .route(KeyStroke.of(KeyStroke.Key.S, KeyStroke.Modifier.CTRL)));
    }

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

        assertEquals(List.of(ConfirmChoice.SAVE, ConfirmChoice.DISCARD, ConfirmChoice.CANCEL),
            placement.focusScope().tabOrder());
        assertEquals(ConfirmChoice.CANCEL, fixture.focus.focused().orElseThrow());
        assertEquals(ConfirmChoice.CANCEL, fixture.route(KeyStroke.of(KeyStroke.Key.ENTER)).decision(),
            "Recovery starts at Later rather than restoring or deleting drafts");
        assertEquals(ConfirmationAction.Command.QUIT,
            fixture.route(KeyStroke.of(KeyStroke.Key.Q, KeyStroke.Modifier.CTRL)).decision());
        assertEquals(ConfirmChoice.CANCEL, fixture.focus.focused().orElseThrow(),
            "The shortcut-only Quit command does not become another focus target");
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
    void applicationPoliciesRouteNoToDiscardOnlyWhenConfirmingUnsavedChanges() {
        Fixture quit = standard(ConfirmBarInputRouter.Policy.quit(), 80);
        assertEquals(ConfirmChoice.SAVE, quit.route(new TextInput("Y")).decision());
        assertEquals(ConfirmChoice.DISCARD, quit.route(new TextInput("n")).decision());
        assertEquals(
            ConfirmChoice.CANCEL,
            quit.route(KeyStroke.of(KeyStroke.Key.ESCAPE)).decision()
        );
        assertEquals(
            ConfirmChoice.DISCARD,
            quit.route(KeyStroke.of(KeyStroke.Key.Q, KeyStroke.Modifier.CTRL)).decision()
        );

        Fixture overwrite = standard(ConfirmBarInputRouter.Policy.overwrite(), 80);
        assertEquals(ConfirmChoice.SAVE, overwrite.route(new TextInput("y")).decision());
        assertEquals(ConfirmChoice.CANCEL, overwrite.route(new TextInput("N")).decision());

        Fixture closeTab = standard(ConfirmBarInputRouter.Policy.closeTab(), 80);
        assertEquals(ConfirmChoice.SAVE, closeTab.route(new TextInput("y")).decision());
        assertEquals(ConfirmChoice.DISCARD, closeTab.route(new TextInput("N")).decision());
        assertEquals(ConfirmChoice.CANCEL, closeTab.route(KeyStroke.of(KeyStroke.Key.ESCAPE)).decision());

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
    void recoveryAcceptsExactDeleteLettersAndKeepsNoAndEscapeForLater() {
        for (String letter : List.of("d", "D")) {
            Fixture recovery = fixture(ConfirmBarView.buildRecovery("Unsaved drafts: 3.", 80),
                ConfirmBarInputRouter.Policy.recovery(), 80, new Object());
            assertEquals(ConfirmChoice.DISCARD, recovery.route(new TextInput(letter)).decision());
            assertEquals(ConfirmChoice.DISCARD, recovery.focus.focused().orElseThrow());
        }
        for (TuiInputEvent event : List.of(new TextInput("n"), new TextInput("N"),
            KeyStroke.of(KeyStroke.Key.ESCAPE))) {
            Fixture recovery = fixture(ConfirmBarView.buildRecovery("Unsaved drafts: 3.", 80),
                ConfirmBarInputRouter.Policy.recovery(), 80, new Object());
            assertEquals(ConfirmChoice.CANCEL, recovery.route(event).decision(), event.toString());
        }
        Fixture recovery = fixture(ConfirmBarView.buildRecovery("Unsaved drafts: 3.", 80),
            ConfirmBarInputRouter.Policy.recovery(), 80, new Object());
        for (String text : List.of("delete", "dd", "D ", "Ｄ", "q", "Q", "unrelated"))
            assertCapturedWithoutDecision(recovery.route(new TextInput(text)));
        assertEquals(ConfirmChoice.CANCEL, recovery.focus.focused().orElseThrow());
    }

    @Test
    void deleteIsUnboundOutsideRecoveryIncludingTheIndependentOpenPolicy() {
        assertFalse(ConfirmBarInputRouter.Policy.open() == ConfirmBarInputRouter.Policy.recovery());
        assertEquals(ConfirmationShortcuts.STANDARD, ConfirmBarInputRouter.Policy.open().shortcuts());
        assertEquals(ConfirmationShortcuts.RECOVERY, ConfirmBarInputRouter.Policy.recovery().shortcuts());
        for (var policy : List.of(ConfirmBarInputRouter.Policy.open(), ConfirmBarInputRouter.Policy.overwrite(),
            ConfirmBarInputRouter.Policy.quit(), ConfirmBarInputRouter.Policy.closeTab())) {
            Fixture fixture = standard(policy, 80);
            for (String letter : List.of("d", "D")) {
                assertCapturedWithoutDecision(fixture.route(new TextInput(letter)));
                assertEquals(ConfirmChoice.CANCEL, fixture.focus.focused().orElseThrow());
            }
        }
    }

    @Test
    void exactCtrlQKeepsTheIntendedDecisionInAllConfirmationModes() {
        List<Fixture> fixtures = allConfirmationModes();
        List<ConfirmationAction> expected = List.of(
            ConfirmationAction.Command.QUIT, ConfirmChoice.CANCEL,
            ConfirmChoice.DISCARD, ConfirmChoice.CANCEL, ConfirmChoice.CANCEL
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
        assertEquals(ConfirmChoice.DISCARD, samePrompt.route(new TextInput("n")).decision());
    }

    @Test
    void hiddenInitialButtonLeavesFocusClearUntilAnExplicitChoice() {
        Fixture fixture = standard(ConfirmBarInputRouter.Policy.quit(), 1);

        assertTrue(fixture.focus.focused().isEmpty());
        assertFalse(fixture.target(ConfirmChoice.SAVE).focusEligible());
        assertFalse(fixture.target(ConfirmChoice.CANCEL).focusEligible());
        assertCapturedWithoutDecision(fixture.route(KeyStroke.of(KeyStroke.Key.ENTER)));
        fixture.route(KeyStroke.of(KeyStroke.Key.END));
        assertTrue(fixture.focus.focused().isEmpty());
    }

    @Test
    void actionWidthWithoutTheQuestionDoesNotOfferAnyChoice() {
        Fixture fixture = standard(ConfirmBarInputRouter.Policy.quit(), 6);

        assertFalse(fixture.target(ConfirmChoice.SAVE).focusEligible());
        assertFalse(fixture.target(ConfirmChoice.CANCEL).focusEligible());
        assertTrue(fixture.focus.focused().isEmpty());
        assertCapturedWithoutDecision(fixture.route(KeyStroke.of(KeyStroke.Key.ENTER)));

        fixture.route(KeyStroke.of(KeyStroke.Key.TAB));
        assertTrue(fixture.focus.focused().isEmpty());
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
        assertFalse(narrow.focusScope().target(ConfirmChoice.SAVE).orElseThrow().focusEligible());
    }

    @Test
    void compactConfirmationsAllowOnlySafeCancellationOrRecoveryQuitAcrossEveryPolicy() {
        List<ConfirmBarInputRouter.Policy> policies = List.of(
            ConfirmBarInputRouter.Policy.recovery(), ConfirmBarInputRouter.Policy.overwrite(),
            ConfirmBarInputRouter.Policy.quit(), ConfirmBarInputRouter.Policy.closeTab(),
            ConfirmBarInputRouter.Policy.open()
        );
        List<TuiInputEvent> events = List.of(
            new TextInput("y"), new TextInput("Y"), new TextInput("n"), new TextInput("N"),
            new TextInput("d"), new TextInput("D"),
            KeyStroke.of(KeyStroke.Key.ESCAPE), KeyStroke.of(KeyStroke.Key.Q, KeyStroke.Modifier.CTRL),
            KeyStroke.of(KeyStroke.Key.ENTER), new TextInput(" "), KeyStroke.of(KeyStroke.Key.TAB),
            KeyStroke.of(KeyStroke.Key.HOME), KeyStroke.of(KeyStroke.Key.END),
            pointer(4, 0, PointerEvent.Kind.PRESS, PointerEvent.Button.LEFT)
        );
        for (ConfirmBarInputRouter.Policy policy : policies) {
            ConfirmBarView view = policy == ConfirmBarInputRouter.Policy.recovery()
                ? ConfirmBarView.buildRecovery("Recover?", 12)
                : ConfirmBarView.build("Confirm?", 12);
            Fixture fixture = fixture(view, policy, 12, new Object());
            for (TuiInputEvent event : events) {
                ConfirmBarInputRouter.Result result = fixture.route(event);
                boolean safeCancel = event instanceof TextInput text && text.text().equalsIgnoreCase("n")
                        && policy.shortcuts().decision(ConfirmationShortcuts.Key.NO) == ConfirmChoice.CANCEL
                    || event.equals(KeyStroke.of(KeyStroke.Key.ESCAPE))
                    || event.equals(KeyStroke.of(KeyStroke.Key.Q, KeyStroke.Modifier.CTRL))
                        && policy != ConfirmBarInputRouter.Policy.quit() && policy != ConfirmBarInputRouter.Policy.recovery();
                boolean recoveryQuit = policy == ConfirmBarInputRouter.Policy.recovery()
                    && event.equals(KeyStroke.of(KeyStroke.Key.Q, KeyStroke.Modifier.CTRL));
                assertTrue(result.consumed(), event.toString());
                assertEquals(recoveryQuit ? ConfirmationAction.Command.QUIT : safeCancel ? ConfirmChoice.CANCEL : null,
                    result.decision(), event.toString());
                assertTrue(fixture.focus.focused().isEmpty());
            }
        }
    }

    @Test
    void compactModeCannotBeBypassedByEnteringAPreviouslyFocusedChoice() {
        Object scopeId = new Object();
        Fixture wide = fixture(ConfirmBarView.build("Quit?", 80),
            ConfirmBarInputRouter.Policy.quit(), 80, scopeId);
        wide.focus.focus(wide.scope(), ConfirmChoice.DISCARD);
        ConfirmBarView.Placement narrow = ConfirmBarView.build("Quit?", 12)
            .place(scopeId, 4, viewport(6, 12));
        Fixture compact = new Fixture(wide.focus, narrow, wide.policy);

        assertCapturedWithoutDecision(compact.route(KeyStroke.of(KeyStroke.Key.ENTER)));
        assertCapturedWithoutDecision(compact.route(new TextInput("y")));
        assertTrue(compact.focus.focused().isEmpty());

        ConfirmBarView.Placement restored = ConfirmBarView.build("Quit?", 80)
            .place(scopeId, 4, viewport(6, 80));
        Fixture expanded = new Fixture(wide.focus, restored, wide.policy);
        assertEquals(ConfirmChoice.SAVE, expanded.route(new TextInput("y")).decision());
    }

    @Test
    void mnemonicCannotActivateAClippedActionEvenWhenTheQuestionIsVisible() {
        ConfirmBarView.Placement placement = ConfirmBarView.build("Quit?", 80)
            .place(new Object(), 4, viewport(6, 55));
        Fixture fixture = new Fixture(new FocusManager<>(), placement, ConfirmBarInputRouter.Policy.quit());

        assertTrue(placement.decisionsAvailable());
        assertTrue(fixture.target(ConfirmChoice.SAVE).enabled());
        assertFalse(fixture.target(ConfirmChoice.SAVE).interactive());
        assertCapturedWithoutDecision(fixture.route(new TextInput("y")));
        assertCapturedWithoutDecision(fixture.route(new TextInput("n")));
        assertCapturedWithoutDecision(
            fixture.route(KeyStroke.of(KeyStroke.Key.Q, KeyStroke.Modifier.CTRL)));
        assertEquals(ConfirmChoice.CANCEL, fixture.route(KeyStroke.of(KeyStroke.Key.ESCAPE)).decision());
    }

    @Test
    void deleteCannotActivateAClippedRecoveryButtonWhileTheQuestionRemainsVisible() {
        var view = ConfirmBarView.buildRecovery("Unsaved drafts: 3.", 80);
        var full = view.place(new Object(), 4, viewport(6, 80));
        int clippedWidth = full.focusScope().target(ConfirmChoice.DISCARD).orElseThrow()
            .placement().bounds().rightExclusive() - 1;
        var placement = view.place(new Object(), 4, viewport(6, clippedWidth));
        Fixture recovery = new Fixture(new FocusManager<>(), placement, ConfirmBarInputRouter.Policy.recovery());

        assertTrue(placement.decisionsAvailable());
        assertTrue(recovery.target(ConfirmChoice.DISCARD).enabled());
        assertFalse(recovery.target(ConfirmChoice.DISCARD).interactive());
        assertCapturedWithoutDecision(recovery.route(new TextInput("d")));
        assertCapturedWithoutDecision(recovery.route(new TextInput("D")));
        assertEquals(ConfirmChoice.CANCEL, recovery.route(new TextInput("n")).decision());
        assertEquals(ConfirmationAction.Command.QUIT,
            recovery.route(KeyStroke.of(KeyStroke.Key.Q, KeyStroke.Modifier.CTRL)).decision());
    }

    @Test
    void aClippedRecoveryShortcutLineDisablesDeleteEvenWhenAllButtonsFit() {
        var view = ConfirmBarView.responsive("Unsaved drafts: 3.", 70, 2,
            "Restore", "Delete", true, "Later", ConfirmationShortcuts.RECOVERY);
        var placement = view.place(new Object(), 0, new Rect(0, 1, 70, 1));
        Fixture recovery = new Fixture(new FocusManager<>(), placement, ConfirmBarInputRouter.Policy.recovery());

        assertFalse(placement.decisionsAvailable(), "The keys explaining Delete must be visible as well");
        assertFalse(recovery.target(ConfirmChoice.DISCARD).enabled());
        assertCapturedWithoutDecision(recovery.route(new TextInput("d")));
        assertCapturedWithoutDecision(recovery.route(new TextInput("D")));
        assertCapturedWithoutDecision(recovery.route(KeyStroke.of(KeyStroke.Key.ENTER)));
        assertEquals(ConfirmChoice.CANCEL, recovery.route(KeyStroke.of(KeyStroke.Key.ESCAPE)).decision());
        assertEquals(ConfirmationAction.Command.QUIT,
            recovery.route(KeyStroke.of(KeyStroke.Key.Q, KeyStroke.Modifier.CTRL)).decision(),
            "Safe application exit remains available when the shortcut hint and dangerous choices are clipped");
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
            standard(ConfirmBarInputRouter.Policy.closeTab(), 80),
            standard(ConfirmBarInputRouter.Policy.open(), 80)
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
