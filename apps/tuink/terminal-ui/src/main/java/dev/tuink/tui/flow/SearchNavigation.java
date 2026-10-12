package dev.tuink.tui.flow;

import java.util.List;
import java.util.Objects;

/** Logical search rows shared by focus scopes and semantic navigation. */
public final class SearchNavigation {
    private static final List<List<SearchFocus>> ROWS = List.of(
        List.of(SearchFocus.FIND, SearchFocus.CASE, SearchFocus.WORD, SearchFocus.WILDCARD, SearchFocus.ALL),
        List.of(
            SearchFocus.REPLACE,
            SearchFocus.NEXT_BUTTON,
            SearchFocus.PREV_BUTTON,
            SearchFocus.REPLACE_BUTTON,
            SearchFocus.ALL_BUTTON
        )
    );
    private static final List<SearchFocus> ORDER = ROWS.stream().flatMap(List::stream).toList();

    private SearchNavigation() {
    }

    public static List<SearchFocus> order() {
        return ORDER;
    }

    public static SearchFocus vertical(SearchFocus focus, boolean up) {
        int index = ORDER.indexOf(Objects.requireNonNull(focus, "focus"));
        int columns = ROWS.getFirst().size();
        int row = index / columns;
        int column = index % columns;
        return ROWS.get(Math.floorMod(row + (up ? -1 : 1), ROWS.size())).get(column);
    }
}
