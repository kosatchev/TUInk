package dev.tuink.tui;

/** The UI that initiated a read and the user intent allowed to activate its result. */
record FileOpenContext(Origin origin, long intentRevision, SearchNavigation search) {
    record SearchNavigation(dev.tuink.project.search.ProjectSearch.Match match, long generation) {}
    FileOpenContext(Origin origin, long intentRevision) { this(origin, intentRevision, null); }
    enum Origin { PICKER, EXPLORER, PROJECT_SEARCH }

    boolean fromExplorer() { return origin != Origin.PICKER; }
}
