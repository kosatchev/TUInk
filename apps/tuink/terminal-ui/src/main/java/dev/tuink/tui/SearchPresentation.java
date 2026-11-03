package dev.tuink.tui;

import dev.tuink.editor.search.SearchOptions;
import dev.tuink.core.SearchLimitException;
import dev.tuink.core.SearchWorkBudget;
import dev.tuink.core.TabSearch;
import dev.tuink.core.TabSession;
import dev.tuink.core.TextModel;
import java.util.List;
import java.util.function.Supplier;

/** Derived search count and tab markers; the controls remain the owners of query values. */
final class SearchPresentation {
    static final long INTERACTIVE_WILDCARD_WORK_LIMIT = 8_000_000L;
    private final SearchMatchCountCache matchCountCache = new SearchMatchCountCache();
    private final TabMatchCache tabMatchCache = new TabMatchCache();
    private final long maximumWork;
    private FailureKey failedKey;
    private Snapshot failedSnapshot;

    SearchPresentation() {
        this(INTERACTIVE_WILDCARD_WORK_LIMIT);
    }

    SearchPresentation(long maximumWork) {
        this.maximumWork = maximumWork;
    }

    record Snapshot(String query, SearchOptions options, int matchCount, String summary, boolean[] tabMatchFlags,
        String error) {}

    private record FailureKey(String query, SearchOptions options, boolean allTabs, long sourceIdentity,
        long sourceRevision) {}

    Snapshot resolve(boolean active, String query, SearchController.SearchUiOptions ui,
        List<TabSession> tabs, int activeIndex) {
        if (!active) {
            failedKey = null;
            return new Snapshot("", SearchOptions.DEFAULT, 0, "", new boolean[tabs.size()], "");
        }
        SearchOptions options = SearchController.toSearchOptions(ui);
        if (query == null || query.isEmpty()) {
            failedKey = null;
            return new Snapshot(query == null ? "" : query, options, 0, "", new boolean[tabs.size()], "");
        }
        boolean allTabs = ui.allTabs();
        TextModel model = tabs.get(activeIndex).model();
        long identity = allTabs ? tabsIdentity(tabs) : model.cacheIdentity();
        long revision = allTabs ? tabsRevision(tabs) : model.revision();
        FailureKey key = new FailureKey(query, options, allTabs, identity, revision);
        if (key.equals(failedKey)) {
            if (failedSnapshot.tabMatchFlags().length != tabs.size()) {
                failedSnapshot = new Snapshot("", options, -1, failedSnapshot.summary(), new boolean[tabs.size()],
                    failedSnapshot.error());
            }
            return failedSnapshot;
        }
        int count;
        try {
            count = SearchWorkBudget.run(maximumWork,
                () -> resolveCount(query, options, tabs, model, allTabs, identity, revision));
            failedKey = null;
        } catch (SearchLimitException limit) {
            failedKey = key;
            // The input owns the original query. An empty render query prevents
            // another expensive scan and -1 never masquerades as zero matches.
            failedSnapshot = new Snapshot("", options, -1, limit.summary(), new boolean[tabs.size()],
                limit.getMessage());
            return failedSnapshot;
        }
        // Optional markers must not invalidate a completed search in its chosen
        // scope. They receive a separate budget, including for inactive tabs.
        boolean[] flags = tabMatchCache.resolve(tabs.size(), query, options, tabsRevision(tabs), maximumWork,
            allTabs ? -1 : activeIndex, count > 0, () -> findTabsWithMatches(tabs, query, options));
        String summary = "Find " + count + (allTabs && tabs.size() > 1 ? " in " + tabs.size() + " tabs" : "");
        return new Snapshot(query, options, count, summary, flags, "");
    }

    private int resolveCount(String query, SearchOptions options, List<TabSession> tabs, TextModel model,
        boolean allTabs, long identity, long revision) {
        int count = matchCountCache.lookup(true, query, options, allTabs, identity, revision);
        if (count < 0) {
            count = allTabs ? TabSearch.countMatches(tabs, query, options)
                : TabSearch.countMatchesInModel(model, query, options);
            matchCountCache.store(query, options, allTabs, identity, revision, count);
        }
        return count;
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
        private boolean failed;
        private int fallbackActiveIndex = -1;
        private boolean[] fallbackFlags = new boolean[0];

        private boolean[] resolve(int tabCount, String query, SearchOptions options,
            long tabsFingerprint, long maximumWork, int activeIndex, boolean activeMatches, Supplier<boolean[]> compute) {
            boolean cached = this.query != null && this.query.equals(query)
                && this.options != null && this.options.equals(options)
                && this.tabsFingerprint == tabsFingerprint && this.flags.length == tabCount;
            if (!cached) {
                try {
                    flags = SearchWorkBudget.run(maximumWork, compute);
                    failed = false;
                } catch (SearchLimitException limit) {
                    // Cache the failed scan as well: cursor-only frames must not
                    // repeatedly traverse a costly inactive document.
                    flags = new boolean[tabCount];
                    failed = true;
                }
                this.query = query;
                this.options = options;
                this.tabsFingerprint = tabsFingerprint;
                fallbackActiveIndex = -1;
            }
            if (failed && activeIndex >= 0) {
                if (fallbackActiveIndex != activeIndex || fallbackFlags[activeIndex] != activeMatches) {
                    fallbackFlags = new boolean[tabCount];
                    fallbackFlags[activeIndex] = activeMatches;
                    fallbackActiveIndex = activeIndex;
                }
                return fallbackFlags;
            }
            return flags;
        }
    }
}
