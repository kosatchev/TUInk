package dev.tuink.render;

/**
 * UI-agnostic scrollbar interaction semantics shared across frontends.
 */
public final class ScrollInteraction {
    public enum Hit {
        NONE,
        THUMB,
        TRACK
    }

    private ScrollInteraction() {
    }

    public static Hit hit(int pointer, int trackStart, int visibleItems, int totalItems, int scroll) {
        if (!hitTrack(pointer, trackStart, visibleItems)) {
            return Hit.NONE;
        }
        int thumbStart = trackStart + thumbOffset(visibleItems, totalItems, scroll);
        int thumbEnd = thumbStart + thumbSize(visibleItems, totalItems) - 1;
        return pointer >= thumbStart && pointer <= thumbEnd ? Hit.THUMB : Hit.TRACK;
    }

    public static boolean hitTrack(int pointer, int trackStart, int visibleItems) {
        return visibleItems > 0 && pointer >= trackStart && pointer < trackStart + visibleItems;
    }

    public static int scrollFromPointer(int pointer, int trackStart, int visibleItems, int totalItems) {
        int maxScroll = maxScroll(visibleItems, totalItems);
        if (maxScroll <= 0 || visibleItems <= 0) {
            return 0;
        }
        int clampedPointer = clamp(pointer, trackStart, trackStart + visibleItems - 1);
        int offset = clampedPointer - trackStart;
        return (int) Math.round((offset / (double) Math.max(1, visibleItems - 1)) * maxScroll);
    }

    public static int pageScrollFromTrackClick(
        int currentScroll,
        int pointer,
        int trackStart,
        int visibleItems,
        int totalItems
    ) {
        int maxScroll = maxScroll(visibleItems, totalItems);
        if (maxScroll <= 0) {
            return 0;
        }
        int clamped = clamp(currentScroll, 0, maxScroll);
        int thumbStart = trackStart + thumbOffset(visibleItems, totalItems, clamped);
        int thumbEnd = thumbStart + thumbSize(visibleItems, totalItems) - 1;
        int page = Math.max(1, visibleItems - 1);
        if (pointer < thumbStart) {
            return clamp(clamped - page, 0, maxScroll);
        }
        if (pointer > thumbEnd) {
            return clamp(clamped + page, 0, maxScroll);
        }
        return clamped;
    }

    public static int thumbSize(int visibleItems, int totalItems) {
        int size = Math.max(
            1,
            (int) Math.round((visibleItems / (double) Math.max(visibleItems, totalItems)) * visibleItems)
        );
        return Math.min(visibleItems, size);
    }

    public static int thumbOffset(int visibleItems, int totalItems, int scroll) {
        int thumbSize = thumbSize(visibleItems, totalItems);
        int thumbTravel = Math.max(0, visibleItems - thumbSize);
        int maxScroll = maxScroll(visibleItems, totalItems);
        int clampedScroll = clamp(scroll, 0, maxScroll);
        return maxScroll == 0 ? 0 : (int) Math.round((clampedScroll / (double) maxScroll) * thumbTravel);
    }

    public static int scrollFromThumbStart(int thumbStartOffset, int visibleItems, int totalItems) {
        int thumbSize = thumbSize(visibleItems, totalItems);
        int thumbTravel = Math.max(0, visibleItems - thumbSize);
        int maxScroll = maxScroll(visibleItems, totalItems);
        if (thumbTravel <= 0 || maxScroll <= 0) {
            return 0;
        }
        int clampedOffset = clamp(thumbStartOffset, 0, thumbTravel);
        return (int) Math.round((clampedOffset / (double) thumbTravel) * maxScroll);
    }

    private static int maxScroll(int visibleItems, int totalItems) {
        return Math.max(0, totalItems - visibleItems);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(value, max));
    }
}
