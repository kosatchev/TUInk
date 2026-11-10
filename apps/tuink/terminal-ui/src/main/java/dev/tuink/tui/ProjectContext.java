package dev.tuink.tui;

import java.nio.file.Path;

/** One window working folder, shared by folder navigation and project tools. */
final class ProjectContext {
    private Path root;
    private long generation;
    Path root() { return root; }
    long generation() { return generation; }
    void open(Path value) { root = value.toAbsolutePath().normalize(); generation++; }
    void close() { root = null; generation++; }
}
