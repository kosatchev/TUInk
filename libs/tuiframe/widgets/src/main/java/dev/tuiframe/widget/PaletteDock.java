package dev.tuiframe.widget;

import dev.tuiframe.geometry.Rect;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/** Vertical, application-owned tool palettes. Layout never changes saved expansion or size preferences. */
public final class PaletteDock {
    private PaletteDock() {}

    public record Item<K>(K id, boolean expanded, int preferredHeight, int minimumHeight) {
        public Item {
            Objects.requireNonNull(id);
            if (preferredHeight < 1 || minimumHeight < 1) throw new IllegalArgumentException("positive body heights required");
        }
    }
    public record Slot<K>(K id, Rect bounds, Rect body) {}
    public record Divider<K>(K before, K after, SplitPane split) {}
    public record Layout<K>(Rect bounds, List<Slot<K>> slots, List<Divider<K>> dividers) {
        public Layout { slots = List.copyOf(slots); dividers = List.copyOf(dividers); }
        public Slot<K> slot(K id) { return slots.stream().filter(slot -> slot.id().equals(id)).findFirst().orElse(null); }
    }

    public static <K> Layout<K> layout(Rect bounds, List<Item<K>> items, K active, K maximized) {
        Objects.requireNonNull(bounds); Objects.requireNonNull(items);
        HashSet<K> ids = new HashSet<>();
        for (var item : items) if (!ids.add(item.id())) throw new IllegalArgumentException("duplicate palette id");
        if (maximized != null && !ids.contains(maximized)) throw new IllegalArgumentException("unknown maximized palette");
        if (maximized != null) {
            var body = new Rect(bounds.x(), bounds.y() + Math.min(1, bounds.height()), bounds.width(), Math.max(0, bounds.height() - 1));
            return new Layout<>(bounds, List.of(new Slot<>(maximized, bounds, body)), List.of());
        }
        if (!items.isEmpty() && bounds.height() < items.size()) {
            K id = ids.contains(active) ? active : items.getFirst().id();
            Rect body = new Rect(bounds.x(), bounds.bottomExclusive(), bounds.width(), 0);
            return new Layout<>(bounds, List.of(new Slot<>(id, bounds, body)), List.of());
        }
        int[] heights = new int[items.size()];
        long minimum = 0, weight = 0;
        int separators = 0;
        for (int i = 0; i < items.size(); i++) if (items.get(i).expanded()) {
            minimum += items.get(i).minimumHeight(); weight += items.get(i).preferredHeight();
            if (i > 0 && items.get(i - 1).expanded()) separators++;
        }
        int remaining = Math.max(0, bounds.height() - items.size());
        if (minimum + separators <= remaining) {
            int cells = remaining - separators;
            long leftWeight = weight;
            boolean[] clamped = new boolean[items.size()];
            boolean again;
            do {
                again = false;
                for (int i = 0; i < items.size(); i++) if (items.get(i).expanded() && !clamped[i]
                    && (long) cells * items.get(i).preferredHeight() < (long) items.get(i).minimumHeight() * leftWeight) {
                    heights[i] = items.get(i).minimumHeight(); clamped[i] = true;
                    cells -= heights[i]; leftWeight -= items.get(i).preferredHeight(); again = true;
                }
            } while (again);
            for (int i = 0; i < items.size(); i++) if (items.get(i).expanded() && !clamped[i]) {
                int share = (int) ((long) cells * items.get(i).preferredHeight() / leftWeight);
                heights[i] = share; cells -= share; leftWeight -= items.get(i).preferredHeight();
            }
        } else {
            int recipient = -1;
            for (int i = 0; i < items.size(); i++) if (items.get(i).expanded()) {
                if (recipient < 0 || items.get(i).id().equals(active)) recipient = i;
            }
            if (recipient >= 0) heights[recipient] = remaining;
        }
        ArrayList<Slot<K>> slots = new ArrayList<>();
        ArrayList<Divider<K>> dividers = new ArrayList<>();
        int y = bounds.y();
        for (int i = 0; i < items.size(); i++) {
            boolean divided = i > 0 && heights[i - 1] > 0 && heights[i] > 0;
            if (divided && y < bounds.bottomExclusive()) y++;
            int height = Math.min(1 + heights[i], bounds.bottomExclusive() - y);
            Rect allocation = new Rect(bounds.x(), y, bounds.width(), height);
            Rect body = new Rect(bounds.x(), y + Math.min(1, height), bounds.width(), Math.max(0, height - 1));
            var slot = new Slot<>(items.get(i).id(), allocation, body);
            if (divided) {
                var before = slots.getLast();
                Rect pair = Rect.fromEdges(bounds.x(), before.bounds().y(), bounds.rightExclusive(), allocation.bottomExclusive());
                dividers.add(new Divider<>(before.id(), slot.id(),
                    SplitPane.resolve(pair, SplitPane.Axis.VERTICAL, before.bounds().height(),
                        items.get(i - 1).minimumHeight() + 1, items.get(i).minimumHeight() + 1)));
            }
            slots.add(slot); y += height;
        }
        return new Layout<>(bounds, slots, dividers);
    }
}
