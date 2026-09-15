package dev.tuink.tui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tuiframe.geometry.Rect;
import dev.tuiframe.input.KeyStroke;
import dev.tuiframe.input.PointerEvent;
import dev.tuiframe.input.TuiInputEvent;
import dev.tuiframe.widget.TabBar;
import dev.tuink.tui.ui.TabBarView;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class TabBarInputRouterTest {
    @Test
    void normalPolicyMapsExactFramePressesAndViewportActions() {
        Fixture fixture = fixture(TabBar.State.initial());

        TabBarInputRouter.Result activated = fixture.route(
            TabBarInputRouter.Policy.NORMAL,
            press(1, 2)
        );
        assertDecision(activated, 0, null, TabBar.State.initial());

        TabBarInputRouter.Result closed = fixture.route(
            TabBarInputRouter.Policy.NORMAL,
            press(1, 4)
        );
        assertDecision(closed, null, 0, TabBar.State.initial());

        TabBarInputRouter.Result arrow = fixture.route(
            TabBarInputRouter.Policy.NORMAL,
            press(1, 13)
        );
        assertDecision(arrow, null, null, new TabBar.State(1));

        TabBarInputRouter.Result wheel = fixture.route(
            TabBarInputRouter.Policy.NORMAL,
            wheel(1, 2, PointerEvent.Kind.SCROLL_DOWN)
        );
        assertDecision(wheel, null, null, new TabBar.State(1));
    }

    @Test
    void unmodifiedVerticalWheelOnTabRowUsesTheTabViewport() {
        Fixture fixture = fixture(TabBar.State.initial());

        TabBarInputRouter.Result chromeWheel = fixture.route(
            TabBarInputRouter.Policy.NORMAL,
            wheel(0, 2, PointerEvent.Kind.SCROLL_DOWN)
        );
        assertDecision(chromeWheel, null, null, new TabBar.State(1));

        assertIgnored(fixture.route(
            TabBarInputRouter.Policy.NORMAL,
            wheel(0, 14, PointerEvent.Kind.SCROLL_DOWN)
        ));
        assertCapturedCurrent(fixture.route(
            TabBarInputRouter.Policy.NORMAL,
            new PointerEvent(
                0,
                2,
                PointerEvent.Kind.SCROLL_DOWN,
                PointerEvent.Button.NONE,
                Set.of(KeyStroke.Modifier.SHIFT)
            )
        ));
    }

    @Test
    void searchAllowsWheelViewportStateButCapturesPressDecisionsAndArrows() {
        Fixture fixture = fixture(TabBar.State.initial());

        assertCapturedCurrent(fixture.route(TabBarInputRouter.Policy.SEARCH, press(1, 2)));
        assertCapturedCurrent(fixture.route(TabBarInputRouter.Policy.SEARCH, press(1, 4)));
        assertCapturedCurrent(fixture.route(TabBarInputRouter.Policy.SEARCH, press(1, 13)));

        TabBarInputRouter.Result wheel = fixture.route(
            TabBarInputRouter.Policy.SEARCH,
            wheel(1, 2, PointerEvent.Kind.SCROLL_DOWN)
        );
        assertDecision(wheel, null, null, new TabBar.State(1));
    }

    @Test
    void closeConfirmationMayScrollButNeverReturnsAnActivateOrCloseDecision() {
        Fixture fixture = fixture(TabBar.State.initial());

        assertCapturedCurrent(fixture.route(
            TabBarInputRouter.Policy.CLOSE_TAB_CONFIRM,
            press(1, 2)
        ));
        assertCapturedCurrent(fixture.route(
            TabBarInputRouter.Policy.CLOSE_TAB_CONFIRM,
            press(1, 4)
        ));

        TabBarInputRouter.Result arrow = fixture.route(
            TabBarInputRouter.Policy.CLOSE_TAB_CONFIRM,
            press(1, 13)
        );
        assertDecision(arrow, null, null, new TabBar.State(1));
    }

    @Test
    void keyboardAndNormalPointerMissesRemainOutsideButModifiedChromeIsCaptured() {
        Fixture fixture = fixture(TabBar.State.initial());

        assertIgnored(fixture.route(
            TabBarInputRouter.Policy.NORMAL,
            KeyStroke.of(KeyStroke.Key.TAB, KeyStroke.Modifier.CTRL)
        ));
        assertIgnored(fixture.route(TabBarInputRouter.Policy.NORMAL, press(2, 2)));
        assertCapturedCurrent(fixture.route(TabBarInputRouter.Policy.NORMAL, new PointerEvent(
            1,
            2,
            PointerEvent.Kind.PRESS,
            PointerEvent.Button.LEFT,
            Set.of(KeyStroke.Modifier.SHIFT)
        )));
        assertIgnored(fixture.route(TabBarInputRouter.Policy.NORMAL, new PointerEvent(
            1,
            2,
            PointerEvent.Kind.RELEASE,
            PointerEvent.Button.LEFT,
            Set.of()
        )));
    }

    @Test
    void clippedPlacementCannotProduceANormalTabDecision() {
        TabBarView.Placement clipped = TabBarView.build(
            items(),
            0,
            TabBar.State.initial()
        ).place(new Rect(0, 1, 14, 1), new Rect(0, 0, 13, 3));

        TabBarInputRouter.Result result = TabBarInputRouter.route(
            clipped,
            TabBarInputRouter.Policy.NORMAL,
            press(1, 2)
        );

        assertIgnored(result);
    }

    private static Fixture fixture(TabBar.State state) {
        TabBarView.Placement placement = TabBarView.build(items(), 0, state)
            .place(new Rect(0, 1, 14, 1), new Rect(0, 0, 14, 3));
        return new Fixture(placement);
    }

    private static List<TabBarView.TabItem> items() {
        return List.of(
            new TabBarView.TabItem(Path.of("a"), false, false),
            new TabBarView.TabItem(Path.of("b"), false, false),
            new TabBarView.TabItem(Path.of("c"), false, false)
        );
    }

    private static PointerEvent press(int row, int column) {
        return new PointerEvent(
            row,
            column,
            PointerEvent.Kind.PRESS,
            PointerEvent.Button.LEFT,
            Set.of()
        );
    }

    private static PointerEvent wheel(int row, int column, PointerEvent.Kind kind) {
        return new PointerEvent(row, column, kind, PointerEvent.Button.NONE, Set.of());
    }

    private static void assertCapturedCurrent(TabBarInputRouter.Result result) {
        assertDecision(result, null, null, TabBar.State.initial());
    }

    private static void assertDecision(
        TabBarInputRouter.Result result,
        Integer activateIndex,
        Integer closeIndex,
        TabBar.State state
    ) {
        assertTrue(result.consumed());
        assertEquals(state, result.state());
        assertEquals(activateIndex, result.activateIndex());
        assertEquals(closeIndex, result.closeIndex());
    }

    private static void assertIgnored(TabBarInputRouter.Result result) {
        assertFalse(result.consumed());
        assertEquals(TabBar.State.initial(), result.state());
        assertNull(result.activateIndex());
        assertNull(result.closeIndex());
    }

    private record Fixture(TabBarView.Placement placement) {
        private TabBarInputRouter.Result route(
            TabBarInputRouter.Policy policy,
            TuiInputEvent event
        ) {
            return TabBarInputRouter.route(placement, policy, event);
        }
    }
}
