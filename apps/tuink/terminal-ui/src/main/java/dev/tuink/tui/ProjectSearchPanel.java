package dev.tuink.tui;

import dev.tuink.editor.document.DocumentId;
import dev.tuink.editor.search.SearchOptions;
import dev.tuink.editor.storage.DocumentOpenMode;
import dev.tuink.project.search.ProjectSearch;
import dev.tuink.project.search.SearchPreferences;
import dev.tuiframe.geometry.Rect;
import dev.tuiframe.widget.FocusManager;
import dev.tuiframe.widget.DropDown;
import dev.tuink.tui.ui.SearchControls;
import dev.tuiframe.widget.ListView;
import dev.tuiframe.widget.TextField;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;

/** Window-owned controls and result view; no worker or filesystem work runs here. */
final class ProjectSearchPanel {
    enum Target { QUERY, CASE, MODE, MODE_STRICT, MODE_WILDCARD, MODE_REGEX, WORD, FILTERS, INCLUDES, EXCLUDES, IGNORE, HIDDEN, ENCODING,
        ALL_FOLDER, FIND, RESULTS, ISSUES }
    enum Kind { NONE, CHANGED, FIND, STOP, OPEN, OPEN_FOLDER, ALL_FOLDER, REJECTED }
    final TextField query = new TextField(TextField.PastePolicy.PRESERVE_TABS);
    final DropDown<SearchOptions.MatchMode> modes = SearchControls.modes(SearchOptions.MatchMode.SUBSTRING);
    final DropDown<DocumentOpenMode> encodings = SearchControls.encodings(DocumentOpenMode.DETECT);
    final TextField includes = new TextField();
    final TextField excludes = new TextField();
    final FocusManager<Target> focus = new FocusManager<>();
    final LinkedHashMap<String, Group> groups = new LinkedHashMap<>();
    final ArrayList<ProjectSearch.Issue> issues = new ArrayList<>();
    final Set<String> collapsed = new HashSet<>();
    SearchOptions.MatchMode mode = SearchOptions.MatchMode.SUBSTRING;
    SearchPreferences preferences = SearchPreferences.defaults();
    boolean caseSensitive, wholeWord, filters, respectIgnore = true, hidden = true, running, stale, showIssues;
    boolean pending, dirty, wideModes;
    boolean hintsVisible = true;
    Target hoveredTarget;
    DocumentOpenMode encoding = DocumentOpenMode.DETECT;
    Path scope;
    List<Row> rows = List.of();
    ListView.State listState = ListView.State.initial();
    Target requestedFocus = Target.QUERY;
    ProjectSearch.Finished finished;
    long scanned, skipped;
    long resultGeneration = -1;
    String error;
    private int lastPressed = -1;
    private long lastPress;

    record Row(String text, String group, ProjectSearch.Match match) {}
    static final class Group {
        final ProjectSearch.Target target;
        final ArrayList<ProjectSearch.Match> matches = new ArrayList<>();
        final java.util.Map<Integer, ArrayList<ProjectSearch.Match>> lines = new java.util.HashMap<>();
        void add(ProjectSearch.Match match) {
            matches.add(match); lines.computeIfAbsent(match.line(), ignored -> new ArrayList<>()).add(match);
        }
        boolean stale;
        Group(ProjectSearch.Target target) { this.target = target; }
    }
    void defaults(SearchPreferences value) {
        preferences = value; respectIgnore = value.respectIgnore(); hidden = value.hidden();
        encoding = value.encoding(); excludes.set(value.exclusions());
    }
    void reset() {
        groups.clear(); issues.clear(); collapsed.clear(); rows = List.of(); listState = ListView.State.initial();
        finished = null; error = null; stale = false; scanned = 0; skipped = 0; showIssues = false;
        pending = false; dirty = false;
        resultGeneration = -1;
        lastPressed = -1;
    }
    void accept(ProjectSearch.Event event) {
        if (event instanceof ProjectSearch.Matches batch) {
            for (var match : batch.matches()) groups.computeIfAbsent(match.target().key(), key -> new Group(match.target())).add(match);
            rebuild();
        } else if (event instanceof ProjectSearch.Progress progress) {
            scanned = progress.files(); skipped = progress.skipped();
        } else if (event instanceof ProjectSearch.Issue issue) issues.add(issue);
        else if (event instanceof ProjectSearch.Finished result) {
            finished = result; scanned = result.files(); skipped = result.skipped(); running = false; pending = false; dirty = false;
            if (result.outcome() == ProjectSearch.Outcome.FAILED) error = result.reason();
        }
    }
    void markStale(DocumentId id, long revision, boolean renamed) {
        boolean changed = false;
        for (var group : groups.values()) if (id.equals(group.target.documentId()) && (group.target.revision() != revision || renamed) && !group.stale) {
            group.stale = true; changed = true;
        }
        if (changed) { stale = true; rebuild(); }
    }
    void rebuild() {
        Row selected = listState.selectedIndex() >= 0 && listState.selectedIndex() < rows.size() ? rows.get(listState.selectedIndex()) : null;
        ArrayList<Row> next = new ArrayList<>();
        if (showIssues) {
            for (var issue : issues) next.add(new Row(issue.label() + ": " + issue.reason(), "", null));
        } else for (var entry : groups.entrySet()) {
            String key = entry.getKey(); Group group = entry.getValue();
            next.add(new Row((collapsed.contains(key) ? "> " : "v ") + group.target.label() + " (" + group.matches.size() + ")"
                + (group.stale ? " [stale]" : group.target.documentId() != null ? " [buffer]" : ""), key, null));
            if (!collapsed.contains(key)) for (var match : group.matches)
                next.add(new Row("  " + (match.line() + 1) + ":" + (match.start() + 1) + "  "
                    + (match.snippetOffset() > 0 ? "…" : "") + match.snippet(), key, match));
        }
        rows = List.copyOf(next);
        int index = selected == null ? Math.max(0, listState.selectedIndex()) : -1;
        if (selected != null) for (int i = 0; i < rows.size(); i++) {
            Row row = rows.get(i);
            if (java.util.Objects.equals(selected.match(), row.match()) && selected.group().equals(row.group())) { index = i; break; }
        }
        listState = new ListView.State(Math.max(0, index), listState.firstVisibleIndex());
    }
    ProjectSearch.Match selectedMatch() {
        int index = listState.selectedIndex();
        return index < 0 || index >= rows.size() ? null : rows.get(index).match();
    }
    TextField focusedField() {
        Target target = focus.focused().orElse(null);
        return target == Target.QUERY ? query : target == Target.INCLUDES ? includes : target == Target.EXCLUDES ? excludes : null;
    }
    void toggleSelected(Boolean expand) {
        int index = listState.selectedIndex();
        if (index < 0 || index >= rows.size() || showIssues) return;
        String key = rows.get(index).group();
        if (expand == null ? !collapsed.contains(key) : !expand) collapsed.add(key); else collapsed.remove(key);
        rebuild();
    }
    int matches() { return groups.values().stream().mapToInt(group -> group.matches.size()).sum(); }
    void dismissPopups() {
        modes.dismissPopup(); encodings.dismissPopup(); hoveredTarget = null;
    }
    static Target modeTarget(SearchOptions.MatchMode value) {
        return switch (value) { case SUBSTRING -> Target.MODE_STRICT; case WILDCARD -> Target.MODE_WILDCARD; case REGEX -> Target.MODE_REGEX; };
    }
    static SearchOptions.MatchMode targetMode(Target target) {
        return switch (target) { case MODE_STRICT -> SearchOptions.MatchMode.SUBSTRING; case MODE_WILDCARD -> SearchOptions.MatchMode.WILDCARD;
            case MODE_REGEX -> SearchOptions.MatchMode.REGEX; default -> throw new IllegalArgumentException("not a mode target"); };
    }
    Kind chooseMode(SearchOptions.MatchMode value) {
        boolean changed = mode != value; mode = value; modes.setValue(value); dismissPopups();
        return changed ? Kind.CHANGED : Kind.NONE;
    }
    Kind adoptList(ListView.Action action) {
        if (action instanceof ListView.StateChanged changed) listState = changed.state();
        else if (action instanceof ListView.ItemPressed pressed) {
            listState = pressed.state();
            long now = System.nanoTime();
            boolean twice = lastPressed == pressed.itemIndex() && now - lastPress < TerminalEditorApp.OPEN_DIALOG_DOUBLE_CLICK_NANOS;
            lastPressed = pressed.itemIndex(); lastPress = now;
            if (twice) { if (selectedMatch() != null) return Kind.OPEN; toggleSelected(null); }
        } else if (action instanceof ListView.Activated) {
            if (selectedMatch() != null) return Kind.OPEN;
            toggleSelected(null);
        }
        return Kind.NONE;
    }
    ProjectSearchView.Placement place(Rect bounds, boolean focused, boolean interactive, Path root) {
        return ProjectSearchView.place(this, bounds, focused, interactive, root);
    }
}
