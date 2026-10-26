package dev.tuink.tui;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.tuink.core.Position;
import dev.tuink.editor.search.SearchOptions;
import dev.tuink.core.SimpleLineBuffer;
import dev.tuink.core.TextModel;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class SearchMatchCountCacheTest {
    @Test
    void cursorOnlyFramesReuseTheCountButEverySemanticInputInvalidatesIt() {
        SearchMatchCountCache cache = new SearchMatchCountCache();
        AtomicInteger computes = new AtomicInteger();
        SearchOptions options = SearchOptions.DEFAULT;

        assertEquals(7, resolve(cache, true, "needle", options, false, 11, 3, computes));
        assertEquals(7, resolve(cache, true, "needle", options, false, 11, 3, computes));
        assertEquals(1, computes.get());

        assertEquals(7, resolve(cache, true, "other", options, false, 11, 3, computes));
        assertEquals(7, resolve(
            cache,
            true,
            "other",
            new SearchOptions(true, SearchOptions.MatchMode.SUBSTRING),
            false,
            11,
            3,
            computes
        ));
        assertEquals(7, resolve(cache, true, "other", options, false, 12, 3, computes));
        assertEquals(7, resolve(cache, true, "other", options, true, 12, 3, computes));
        assertEquals(7, resolve(cache, true, "other", options, true, 12, 4, computes));
        assertEquals(6, computes.get());
    }

    @Test
    void closedOrEmptySearchDoesNotComputeOrPoisonThePreviousEntry() {
        SearchMatchCountCache cache = new SearchMatchCountCache();
        AtomicInteger computes = new AtomicInteger();

        assertEquals(0, resolve(cache, false, "needle", null, false, 1, 1, computes));
        assertEquals(0, resolve(cache, true, "", null, false, 1, 1, computes));
        assertEquals(0, computes.get());
        assertEquals(7, resolve(cache, true, "needle", null, false, 1, 1, computes));
        assertEquals(0, resolve(cache, false, "needle", null, false, 1, 1, computes));
        assertEquals(7, resolve(cache, true, "needle", null, false, 1, 1, computes));
        assertEquals(1, computes.get());
    }

    @Test
    void ordinaryTextModelEditInvalidatesByRevision() {
        SearchMatchCountCache cache = new SearchMatchCountCache();
        AtomicInteger computes = new AtomicInteger();
        TextModel model = new TextModel(new SimpleLineBuffer());

        assertEquals(7, resolve(
            cache,
            true,
            "needle",
            SearchOptions.DEFAULT,
            false,
            model.cacheIdentity(),
            model.revision(),
            computes
        ));
        model.insertChar(new Position(0, 0), 'x');
        assertEquals(0, model.contentEpoch(), "ordinary edits intentionally keep contentEpoch");
        assertEquals(7, resolve(
            cache,
            true,
            "needle",
            SearchOptions.DEFAULT,
            false,
            model.cacheIdentity(),
            model.revision(),
            computes
        ));
        assertEquals(2, computes.get());
    }

    private static int resolve(
        SearchMatchCountCache cache,
        boolean active,
        String query,
        SearchOptions options,
        boolean allTabs,
        long identity,
        long revision,
        AtomicInteger computes
    ) {
        int cached = cache.lookup(active, query, options, allTabs, identity, revision);
        if (cached >= 0) {
            return cached;
        }
        computes.incrementAndGet();
        cache.store(query, options, allTabs, identity, revision, 7);
        return 7;
    }
}
