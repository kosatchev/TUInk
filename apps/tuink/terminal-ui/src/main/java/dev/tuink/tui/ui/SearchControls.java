package dev.tuink.tui.ui;

import dev.tuiframe.widget.DropDown;
import dev.tuink.editor.search.SearchOptions;
import dev.tuink.editor.storage.DocumentOpenMode;
import java.util.List;

/** Shared search vocabulary; interaction and control styling belong to TUIframe. */
public final class SearchControls {
    private SearchControls() {}
    public static DropDown<SearchOptions.MatchMode> modes(SearchOptions.MatchMode selected) {
        return new DropDown<>(List.of(
            new DropDown.Option<>(SearchOptions.MatchMode.SUBSTRING, "Strict", "literal text"),
            new DropDown.Option<>(SearchOptions.MatchMode.WILDCARD, "Wildcard", "* and ?"),
            new DropDown.Option<>(SearchOptions.MatchMode.REGEX, "Regex", "expressions")), selected);
    }
    public static DropDown<DocumentOpenMode> encodings(DocumentOpenMode selected) {
        return new DropDown<>(List.of(
            new DropDown.Option<>(DocumentOpenMode.DETECT, "Auto"),
            new DropDown.Option<>(DocumentOpenMode.UTF_8, "UTF-8"),
            new DropDown.Option<>(DocumentOpenMode.UTF_16LE, "UTF-16LE"),
            new DropDown.Option<>(DocumentOpenMode.UTF_16BE, "UTF-16BE"),
            new DropDown.Option<>(DocumentOpenMode.WINDOWS_1251, "1251"),
            new DropDown.Option<>(DocumentOpenMode.CP866, "CP866"),
            new DropDown.Option<>(DocumentOpenMode.KOI8_R, "KOI8-R")), selected);
    }

}
