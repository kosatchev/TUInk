package dev.tuink.tui.ui;

import dev.tuink.terminal.cell.Canvas;
import dev.tuink.terminal.geometry.Rect;
import dev.tuink.terminal.input.PointerEvent;
import dev.tuink.terminal.input.TuiInputEvent;
import dev.tuink.terminal.widget.FocusContext;
import dev.tuink.terminal.widget.FocusSpace;
import dev.tuink.terminal.widget.InteractionResult;
import dev.tuink.terminal.widget.TabBar;
import dev.tuink.terminal.widget.Theme;
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
                formatTabTitle(required.file(), required.dirty(), required.hasMatch()),
                true
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
        String name;
        if (file == null) {
            name = NO_NAME_LABEL;
        } else {
            Path fileName = file.getFileName();
            name = fileName == null ? file.toString() : fileName.toString();
        }
        return (hasMatch ? "#" : "") + name + (dirty ? "*" : "");
    }

    /** Creates one immutable placement shared by painting and raw pointer routing. */
    public Placement place(Rect bounds, Rect interactionClip) {
        Rect requiredBounds = Objects.requireNonNull(bounds, "bounds");
        Rect requiredClip = Objects.requireNonNull(interactionClip, "interactionClip");
        TabBar.Placement widgetPlacement = new TabBar(items, activeIndex, state).layout(
            requiredBounds
        );
        boolean enabled = !widgetPlacement.bounds().isEmpty()
            && requiredClip.contains(widgetPlacement.bounds());
        FocusContext context = new FocusContext(
            false,
            enabled,
            FocusSpace.root(requiredClip)
        );
        return new Placement(widgetPlacement, context);
    }

    /** Editor-owned metadata which is flattened to a generic display label. */
    public record TabItem(Path file, boolean dirty, boolean hasMatch) {
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
