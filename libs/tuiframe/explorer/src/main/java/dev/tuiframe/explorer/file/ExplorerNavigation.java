package dev.tuiframe.explorer.file;

import java.nio.file.Path;

/** Filesystem navigation intents, independent of the explorer's widgets and focus. */
public interface ExplorerNavigation {
    Path root();
    void setRoot(Path root);
    void requestExpansion(FileTreeModel.EntryId id, boolean expand);
    void moveToParentRoot();
    void toggleHiddenFiles();
}
