package dev.tuink.tui;

/** The UI that initiated a read and the user intent allowed to activate its result. */
record FileOpenContext(Origin origin, long intentRevision) {
    enum Origin { PICKER, EXPLORER }

    boolean fromExplorer() { return origin == Origin.EXPLORER; }
}
