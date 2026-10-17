package dev.tuink.tui;

import dev.tuink.core.SearchOptions;
import dev.tuink.core.TabSearch;
import dev.tuink.core.TabSession;
import dev.tuink.core.TextModel;
import java.util.List;
import java.util.function.Supplier;

/** Derived search count and tab markers; the controls remain the owners of query values. */
final class SearchPresentation {
    private final SearchMatchCountCache matchCountCache = new SearchMatchCountCache();
    private final TabMatchCache tabMatchCache = new TabMatchCache();

    record Snapshot(String query, SearchOptions options, int matchCount, String summary, boolean[] tabMatchFlags) {}

    Snapshot resolve(boolean active, String query, SearchController.SearchUiOptions ui,
        List<TabSession> tabs, int activeIndex) {
        if (!active) {
            return new Snapshot("", SearchOptions.DEFAULT, 0, "", new boolean[tabs.size()]);
        }
        SearchOptions options = SearchController.toSearchOptions(ui);
        if (query == null || query.isEmpty()) {
            return new Snapshot(query == null ? "" : query, options, 0, "", new boolean[tabs.size()]);
        }
        boolean allTabs = ui.allTabs();
        TextModel model = tabs.get(activeIndex).model();
        long identity = allTabs ? tabsIdentity(tabs) : model.cacheIdentity();
        long revision = allTabs ? tabsRevision(tabs) : model.revision();
        int count = matchCountCache.lookup(true, query, options, allTabs, identity, revision);
        if (count < 0) {
            count = allTabs ? TabSearch.countMatches(tabs, query, options)
                : TabSearch.countMatchesInModel(model, query, options);
            matchCountCache.store(query, options, allTabs, identity, revision, count);
        }
        boolean[] flags = tabMatchCache.resolve(tabs.size(), query, options, tabsRevision(tabs),
            () -> findTabsWithMatches(tabs, query, options));
        String summary = "Find " + count + (allTabs && tabs.size() > 1 ? " in " + tabs.size() + " tabs" : "");
        return new Snapshot(query, options, count, summary, flags);
    }

    private static long tabsIdentity(List<TabSession> tabs) {
        long hash = 1469598103934665603L;
        for (TabSession tab : tabs) {
            hash ^= tab.model().cacheIdentity();
            hash *= 1099511628211L;
        }
        return hash ^ tabs.size();
    }

    private static long tabsRevision(List<TabSession> tabs) {
        long hash = 1469598103934665603L;
        for (TabSession tab : tabs) {
            hash ^= tab.model().cacheIdentity();
            hash *= 1099511628211L;
            hash ^= tab.model().revision();
            hash *= 1099511628211L;
        }
        return hash ^ tabs.size();
    }

    private static boolean[] findTabsWithMatches(List<TabSession> tabs, String query, SearchOptions options) {
        int[] matches = TabSearch.findMatchTabs(tabs, query, options);
        boolean[] flags = new boolean[tabs.size()];
        for (int index = 0; index < flags.length && index < matches.length; index++) {
            flags[index] = matches[index] != 0;
        }
        return flags;
    }

    /** Reuses markers across cursor-only frames and unchanged search options. */
    private static final class TabMatchCache {
        private String query;
        private SearchOptions options;
        private long tabsFingerprint = Long.MIN_VALUE;
        private boolean[] flags = new boolean[0];

        private boolean[] resolve(int tabCount, String query, SearchOptions options,
            long tabsFingerprint, Supplier<boolean[]> compute) {
            if (this.query != null && this.query.equals(query)
                && this.options != null && this.options.equals(options)
                && this.tabsFingerprint == tabsFingerprint && this.flags.length == tabCount) {
                return flags;
            }
            this.query = query;
            this.options = options;
            this.tabsFingerprint = tabsFingerprint;
            this.flags = compute.get();
            return flags;
        }
    }
}
