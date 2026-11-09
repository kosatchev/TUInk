package dev.tuink.core;

import static org.junit.jupiter.api.Assertions.*;
import dev.tuink.editor.search.SearchOptions;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class SearchCancellationTest {
    @Test void literalScanChecksCancellationInsideOneLongLine() {
        AtomicInteger checks = new AtomicInteger();
        var context = SearchOperationContext.cancellable(10000, () -> checks.incrementAndGet() > 4);
        var matcher = SearchMatcher.compile("absent", SearchOptions.DEFAULT, context);
        assertThrows(CancellationException.class, () -> matcher.findAll("x".repeat(1_000_000), context));
        assertTrue(checks.get() > 4);
    }
    @Test void nestedBudgetsRetainTheirParentsCancellation() {
        var context = SearchOperationContext.cancellable(10000, () -> true).child(1000);
        assertThrows(CancellationException.class, context::checkpoint);
    }
    @Test void wildcardPrefilterChecksCancellationInsteadOfScanningTheWholeLine() {
        AtomicInteger checks = new AtomicInteger();
        var context = SearchOperationContext.cancellable(1_000_000, () -> checks.incrementAndGet() > 8);
        var matcher = SearchMatcher.compile("a?".repeat(400) + "z", new SearchOptions(false, SearchOptions.MatchMode.WILDCARD), context);
        assertThrows(CancellationException.class, () -> matcher.findAll("a".repeat(1_000_000), context));
    }
}
