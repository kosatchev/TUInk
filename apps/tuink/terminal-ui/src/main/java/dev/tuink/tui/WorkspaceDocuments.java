package dev.tuink.tui;

import dev.tuink.core.TabSession;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Resolves open documents without reloading their text or merging recovery drafts. */
final class WorkspaceDocuments {
    private WorkspaceDocuments() {}

    static int findOpenFile(List<TabSession> tabs, int activeIndex, Path requested) {
        if (activeIndex >= 0 && activeIndex < tabs.size() && matches(tabs.get(activeIndex), requested)) {
            return activeIndex;
        }
        return findOtherOpenFile(tabs, activeIndex, requested);
    }

    static int findOtherOpenFile(List<TabSession> tabs, int excludedIndex, Path requested) {
        for (int index = 0; index < tabs.size(); index++) {
            if (index != excludedIndex && matches(tabs.get(index), requested)) {
                return index;
            }
        }
        return -1;
    }

    static boolean isPristinePlaceholder(TabSession tab) {
        return tab.state().getCurrentFile() == null && !tab.state().isDirty()
            && tab.model().revision() == 0 && tab.model().lineCount() == 1 && tab.model().lineText(0).isEmpty();
    }

    private static boolean matches(TabSession tab, Path requested) {
        Path current = tab.state().getCurrentFile();
        if (current == null || requested == null) {
            return false;
        }
        Path target = requested.toAbsolutePath();
        Path namedTarget;
        try {
            namedTarget = target.getParent().toRealPath().resolve(target.getFileName());
        } catch (IOException | SecurityException unavailable) {
            namedTarget = target;
        }
        Path physical = tab.model().fileBaseline() == null ? current : tab.model().fileBaseline().target();
        if (namedTarget.equals(current)
            && (tab.model().fileBaseline() == null
                || physical.equals(current) && !Files.isSymbolicLink(current))) {
            return true;
        }
        // The spelling used to open a symlink can now point at another file.
        // Alias lookup follows the physical target actually loaded by this document.
        try {
            return physical.equals(physical.toRealPath()) && Files.isSameFile(target, physical);
        } catch (IOException | SecurityException unavailable) {
            return false;
        }
    }
}
