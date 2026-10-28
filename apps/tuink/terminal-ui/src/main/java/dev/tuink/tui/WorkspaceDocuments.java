package dev.tuink.tui;

import dev.tuink.storage.jvm.JvmDocumentStorage;
import dev.tuink.storage.jvm.JvmFileAdvisory;
import dev.tuink.core.TabSession;
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
        return JvmDocumentStorage.pathOf(tab.state().getCurrentResource()) == null && !tab.state().isDirty()
            && tab.model().revision() == 0 && tab.model().lineCount() == 1 && tab.model().lineText(0).isEmpty();
    }

    private static boolean matches(TabSession tab, Path requested) {
        return JvmFileAdvisory.matchesOpenResource(tab.engine().resourceBinding(), requested);
    }
}
