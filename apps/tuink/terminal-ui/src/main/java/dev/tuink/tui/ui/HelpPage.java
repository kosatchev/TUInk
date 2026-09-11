package dev.tuink.tui.ui;

import java.util.List;
import java.util.Objects;

/** Immutable help content shared by routing and exact-frame placement. */
public record HelpPage(String title, List<String> rows) {
    public HelpPage {
        Objects.requireNonNull(title, "title");
        rows = List.copyOf(Objects.requireNonNull(rows, "rows"));
    }

    public HelpPage(String title, String[] rows) {
        this(title, List.of(Objects.requireNonNull(rows, "rows")));
    }
}
