package dev.tuink.tui;

import dev.tuiframe.explorer.file.PathLabels;
import dev.tuiframe.text.TerminalPlainText;
import dev.tuiframe.widget.WidgetStyleRole;
import dev.tuiframe.widget.WidgetThemeKeys;
import dev.tuink.editor.search.SearchOptions;
import dev.tuink.editor.storage.DocumentOpenMode;
import dev.tuink.project.search.ProjectSearch;
import dev.tuink.storage.jvm.JvmDocumentStorage;
import java.nio.file.Path;

/** Search-specific wording and states; independent of terminal geometry and worker execution. */
final class ProjectSearchPresentation {
    private ProjectSearchPresentation() {}
    enum State { EMPTY, READY, PENDING, RUNNING, COMPLETE, NO_MATCHES, STALE, STOPPED, LIMITED, PARTIAL, ERROR }
    record Status(State state, String title, String detail, WidgetStyleRole role) {}

    static String count(ProjectSearchPanel panel) {
        int matches = panel.matches(), files = panel.groups.size();
        return matches + (matches == 1 ? " match in " : " matches in ") + files + (files == 1 ? " file" : " files");
    }
    static Status status(ProjectSearchPanel p) {
        if (p.error != null) {
            boolean regex = p.error.startsWith("Invalid regular expression");
            return new Status(State.ERROR, regex ? "Invalid regular expression" : "Search failed", p.error, WidgetThemeKeys.NOTICE_ERROR);
        }
        if (p.running) return new Status(State.RUNNING, "Searching… " + p.scanned + " files", count(p), WidgetThemeKeys.NOTICE_INFO);
        if (p.pending) return new Status(State.PENDING, "Search pending…", p.stale ? "Results need refreshing" : "", WidgetThemeKeys.NOTICE_INFO);
        if (p.finished != null && p.finished.outcome() == ProjectSearch.Outcome.CANCELLED)
            return new Status(State.STOPPED, "Search stopped", (p.stale ? "Outdated · " : "") + count(p), WidgetThemeKeys.NOTICE_WARNING);
        if (p.stale) return new Status(State.STALE, "Outdated results · refresh", count(p), WidgetThemeKeys.NOTICE_WARNING);
        if (p.query.isEmpty()) return new Status(State.EMPTY, "Search this folder", "", WidgetThemeKeys.NOTICE_INFO);
        if (p.dirty || p.finished == null) return new Status(State.READY, "Press Enter or Search", "", WidgetThemeKeys.NOTICE_INFO);
        return switch (p.finished.outcome()) {
            case CANCELLED -> new Status(State.STOPPED, "Search stopped", count(p), WidgetThemeKeys.NOTICE_WARNING);
            case LIMIT -> new Status(State.LIMITED, "Result limit reached", count(p), WidgetThemeKeys.NOTICE_WARNING);
            case PARTIAL -> new Status(State.PARTIAL, "Some files were skipped", count(p), WidgetThemeKeys.NOTICE_WARNING);
            case FAILED -> new Status(State.ERROR, "Search failed", p.finished.reason(), WidgetThemeKeys.NOTICE_ERROR);
            case COMPLETE -> p.matches() == 0
                ? new Status(State.NO_MATCHES, "No matches", "Try another query or filters", WidgetThemeKeys.NOTICE_INFO)
                : new Status(State.COMPLETE, count(p), "", WidgetThemeKeys.NOTICE_SUCCESS);
        };
    }
    static int activeFilters(ProjectSearchPanel p, Path root) {
        return (p.includes.isEmpty() ? 0 : 1) + (p.excludes.isEmpty() ? 0 : 1) + (p.respectIgnore ? 1 : 0)
            + (p.hidden ? 0 : 1) + (p.encoding == DocumentOpenMode.DETECT ? 0 : 1)
            + (p.scope != null && !p.scope.equals(root) ? 1 : 0);
    }
    static String modeHelp(SearchOptions.MatchMode mode) {
        return switch (mode) {
            case SUBSTRING -> "Strict: literal text";
            case WILDCARD -> "Wildcard: * any, ? one";
            case REGEX -> "Regex: Java expressions";
        };
    }
    static String hint(ProjectSearchPanel p, Path root) {
        var target = p.hoveredTarget != null ? p.hoveredTarget : p.focus.focused().orElse(null);
        if (target == null) return "Enter: search · Esc: editor";
        return switch (target) {
            case MODE -> modeHelp(p.mode);
            case MODE_STRICT, MODE_WILDCARD, MODE_REGEX -> modeHelp(ProjectSearchPanel.targetMode(target));
            case CASE -> "Aa: match letter case";
            case WORD -> "Word: match complete words";
            case FILTERS -> activeFilters(p, root) + (activeFilters(p, root) == 1 ? " active file filter" : " active file filters");
            case INCLUDES -> "Include: *.java, *.md";
            case EXCLUDES -> "Exclude: build/**, *.log";
            case IGNORE -> "Add files excluded by .gitignore";
            case HIDDEN -> "Add hidden files and folders";
            case ENCODING -> "Encoding used to read files";
            case FIND -> p.running || p.pending ? "Stop the current search" : p.stale ? "Refresh outdated results" : "Search the working folder";
            case RESULTS -> "Enter: open · ←/→: fold";
            case ISSUES -> "Inspect skipped files";
            case ALL_FOLDER -> root == null ? "Choose a working folder" : "Search the entire folder";
            case QUERY -> modeHelp(p.mode);
        };
    }
    static String selectionStatus(ProjectSearchPanel p) {
        int index = p.listState.selectedIndex();
        if (index < 0 || index >= p.rows.size()) return "";
        var row = p.rows.get(index);
        var group = p.groups.get(row.group());
        if (group == null) return TerminalPlainText.singleLine(row.text());
        Path path = JvmDocumentStorage.pathOf(group.target.resource());
        String label = path == null ? group.target.label() : PathLabels.full(path);
        var match = row.match();
        return (match == null ? "File · " : "Ln " + (match.line() + 1) + ", Col " + (match.start() + 1) + " · ")
            + TerminalPlainText.singleLine(label);
    }
    static String collapsedTitle(ProjectSearchPanel p) {
        if (p.error != null) return "Search · error";
        if (p.running || p.pending) return "Search · searching";
        if (p.finished != null && p.finished.outcome() == ProjectSearch.Outcome.CANCELLED) return "Search · stopped";
        if (p.stale) return "Search · outdated";
        if (p.finished != null) {
            if (p.finished.outcome() == ProjectSearch.Outcome.LIMIT) return "Search · limited";
            if (p.finished.outcome() == ProjectSearch.Outcome.PARTIAL) return "Search · incomplete";
            if (p.finished.outcome() == ProjectSearch.Outcome.FAILED) return "Search · error";
            if (!p.query.isEmpty() && p.matches() == 0) return "Search · no matches";
        }
        return "Search" + (p.matches() > 0 ? " · " + p.matches() + (p.matches() == 1 ? " match" : " matches") : "");
    }
}
