package dev.tuink.tui.flow;

import dev.tuink.core.TabSession;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.IdentityHashMap;
import java.util.Map;

public final class SaveAsSuggestionStore {
    private final Map<TabSession, String> draftsByTab = new IdentityHashMap<>();

    public String suggestionFor(TabSession tabSession, Path currentFile, String defaultFileName) {
        String draft = draftsByTab.get(tabSession);
        if (draft != null && !draft.isBlank()) {
            return draft;
        }
        if (currentFile != null) {
            return currentFile.toAbsolutePath().normalize().toString();
        }
        Path preferred = Path.of("").toAbsolutePath().normalize().resolve(defaultFileName);
        return SaveTargetSuggester.firstAvailable(preferred, Files::exists).toString();
    }

    public void rememberDraft(TabSession tabSession, String text) {
        if (tabSession == null) {
            return;
        }
        if (text == null) {
            draftsByTab.remove(tabSession);
            return;
        }
        draftsByTab.put(tabSession, text);
    }

    public void rememberSavedPath(TabSession tabSession, Path savedPath) {
        if (tabSession == null || savedPath == null) {
            return;
        }
        draftsByTab.put(tabSession, savedPath.toAbsolutePath().normalize().toString());
    }

    public void remove(TabSession tabSession) {
        if (tabSession == null) {
            return;
        }
        draftsByTab.remove(tabSession);
    }
}
