package dev.tuink.tui.ui;

import dev.tuiframe.cell.Canvas;
import dev.tuiframe.geometry.Rect;
import dev.tuiframe.input.PointerEvent;
import dev.tuiframe.input.TuiInputEvent;
import dev.tuiframe.widget.FocusContext;
import dev.tuiframe.widget.FocusSpace;
import dev.tuiframe.widget.InteractionVisibility;
import dev.tuiframe.widget.InteractionResult;
import dev.tuiframe.widget.TabBar;
import dev.tuiframe.widget.Theme;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** TUInk-owned tab metadata adapter over the reusable tab-bar widget. */
public final class TabBarView {
    public static final String NO_NAME_LABEL = "--no name--";

    private final List<TabBar.Item> items;
    private final int activeIndex;
    private final TabBar.State state;

    private TabBarView(List<TabItem> tabs, int activeIndex, TabBar.State state) {
        Objects.requireNonNull(tabs, "tabs");
        ArrayList<TabBar.Item> mapped = new ArrayList<>(tabs.size());
        for (TabItem tab : List.copyOf(tabs)) {
            TabItem required = Objects.requireNonNull(tab, "tab");
            mapped.add(new TabBar.Item(
                caption(required.file(), required.qualifier()), true,
                prefix(required.hasMatch(), required.searching(), required.awaitingAnswer()), required.dirty() ? "*" : "",
                DocumentCaption.extensionCells(DocumentCaption.name(required.file()))
                    + dev.tuiframe.cell.CellText.width(qualifier(required.qualifier())), PathPresentation.LABEL_POLICY
            ));
        }
        this.items = List.copyOf(mapped);
        this.activeIndex = activeIndex;
        this.state = Objects.requireNonNull(state, "state");
    }

    public static TabBarView build(List<TabItem> tabs, int activeIndex, TabBar.State state) {
        return new TabBarView(tabs, activeIndex, state);
    }

    /**
     * Formats editor metadata for the generic tab-bar widget
     * and the Tabs menu.
     */
    public static String formatTabTitle(Path file, boolean dirty, boolean hasMatch) {
        return formatTabTitle(file, dirty, hasMatch, false, false);
    }

    public static String formatTabTitle(Path file, boolean dirty, boolean hasMatch,
                                        boolean searching, boolean awaitingAnswer) {
        String name = DocumentCaption.name(file);
        return prefix(hasMatch, searching, awaitingAnswer) + name + (dirty ? "*" : "");
    }

    public static String formatTabTitle(Path file, boolean dirty, boolean hasMatch,
        boolean searching, boolean awaitingAnswer, String qualifier) {
        return prefix(hasMatch, searching, awaitingAnswer) + caption(file, qualifier) + (dirty ? "*" : "");
    }

    private static String qualifier(String qualifier) {
        return qualifier == null || qualifier.isBlank() ? "" : " · " + qualifier;
    }
    private static String caption(Path file, String qualifier) { return DocumentCaption.name(file) + qualifier(qualifier); }

    private static String prefix(boolean hasMatch, boolean searching, boolean awaitingAnswer) {
        return (awaitingAnswer ? "?" : "") + (searching ? "/" : "") + (hasMatch ? "#" : "");
    }

    /** Creates one immutable placement shared by painting and raw pointer routing. */
    public Placement place(Rect bounds, Rect interactionClip) {
        return place(bounds, interactionClip, false);
    }

    /** Reveals the active tab after activation or resize while retaining nearby tabs. */
    public Placement placeRevealingActive(Rect bounds, Rect interactionClip) {
        return place(bounds, interactionClip, true);
    }

    /** Keeps a visible active tab in view when its label or preceding labels change. */
    public Placement placeKeepingActiveVisible(Rect bounds, Rect interactionClip, Placement previous) {
        Objects.requireNonNull(previous, "previous");
        TabBar.Placement widgetPlacement = new TabBar(items, activeIndex, state)
            .layoutKeepingActiveVisible(Objects.requireNonNull(bounds, "bounds"), previous.widgetPlacement);
        return placement(widgetPlacement, interactionClip);
    }

    private Placement place(Rect bounds, Rect interactionClip, boolean revealActive) {
        Rect requiredBounds = Objects.requireNonNull(bounds, "bounds");
        TabBar widget = new TabBar(items, activeIndex, state);
        TabBar.Placement widgetPlacement = revealActive
            ? widget.layoutRevealingActive(requiredBounds)
            : widget.layout(requiredBounds);
        return placement(widgetPlacement, interactionClip);
    }

    private Placement placement(TabBar.Placement widgetPlacement, Rect interactionClip) {
        Rect requiredClip = Objects.requireNonNull(interactionClip, "interactionClip");
        boolean enabled = InteractionVisibility.FULL_BOUNDS.allows(widgetPlacement.bounds(), requiredClip);
        FocusContext context = new FocusContext(
            false,
            enabled,
            FocusSpace.root(requiredClip)
        );
        return new Placement(widgetPlacement, context);
    }

    /** Editor-owned metadata mapped to a generic label and separately retained affixes. */
    public record TabItem(Path file, boolean dirty, boolean hasMatch, boolean searching, boolean awaitingAnswer,
                          String qualifier) {
        public TabItem(Path file, boolean dirty, boolean hasMatch, boolean searching, boolean awaitingAnswer) {
            this(file, dirty, hasMatch, searching, awaitingAnswer, "");
        }
        public TabItem(Path file, boolean dirty, boolean hasMatch) {
            this(file, dirty, hasMatch, false, false);
        }
    }

    /** Exact current-frame widget placement and coordinate context. */
    public static final class Placement {
        private final TabBar.Placement widgetPlacement;
        private final FocusContext context;

        private Placement(TabBar.Placement widgetPlacement, FocusContext context) {
            this.widgetPlacement = Objects.requireNonNull(widgetPlacement, "widgetPlacement");
            this.context = Objects.requireNonNull(context, "context");
        }

        public Rect bounds() {
            return widgetPlacement.bounds();
        }

        public TabBar.State state() {
            return widgetPlacement.state();
        }

        public boolean enabled() {
            return context.enabled();
        }

        /** Paints the exact placement used for this frame's hit routing. */
        public void paint(Canvas canvas, Theme theme) {
            widgetPlacement.paint(
                Objects.requireNonNull(canvas, "canvas"),
                Objects.requireNonNull(theme, "theme"),
                context.widgetState()
            );
        }

        /** Routes through the same placement and parent-local mapping used by paint. */
        public InteractionResult<TabBar.Action> handle(TuiInputEvent event) {
            return widgetPlacement.handle(Objects.requireNonNull(event, "event"), context);
        }

        /** Returns whether a physical pointer lies inside this visible placement. */
        public boolean contains(PointerEvent event) {
            Objects.requireNonNull(event, "event");
            return context.space().localPoint(event)
                .filter(widgetPlacement::hit)
                .isPresent();
        }
    }
}
