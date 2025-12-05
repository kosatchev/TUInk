package dev.tuink.core;

import java.util.List;

/**
 * Reusable tab-list operations independent from concrete UI.
 */
public final class TabSessions {
    private TabSessions() {
    }

    public static int switchByDelta(int size, int currentIndex, int delta) {
        if (size <= 0) {
            return 0;
        }
        return Math.floorMod(currentIndex + delta, size);
    }

    public static int closeIndex(int size, int activeIndex, int closeIndex) {
        if (size <= 1) {
            return 0;
        }
        if (closeIndex < activeIndex) {
            return Math.max(0, activeIndex - 1);
        }
        return Math.max(0, Math.min(activeIndex, size - 2));
    }

    public static boolean hasDirtyTabs(List<TabSession> tabs) {
        return countDirtyTabs(tabs) > 0;
    }

    public static int countDirtyTabs(List<TabSession> tabs) {
        int count = 0;
        for (TabSession tab : tabs) {
            if (tab.state().isDirty()) {
                count++;
            }
        }
        return count;
    }
}
