package dev.tuink.tui.flow;

import dev.tuink.core.TabSession;
import java.nio.file.Path;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Predicate;
import java.util.function.Supplier;

public final class SaveAsSuggestionStore {
    private record Draft(String remembered, String generated) {}

    private final Map<TabSession, Draft> draftsByTab = new IdentityHashMap<>();

    public String suggestionFor(TabSession tabSession, Supplier<String> generateSuggestion) {
        return suggestionFor(tabSession, generateSuggestion, draft -> true);
    }

    /** Application policy may reject a draft which no longer offers a usable save target. */
    public String suggestionFor(TabSession tabSession, Supplier<String> generateSuggestion,
        Predicate<String> suitableDraft) {
        Objects.requireNonNull(generateSuggestion, "generateSuggestion");
        Objects.requireNonNull(suitableDraft, "suitableDraft");
        Draft draft = draftsByTab.get(tabSession);
        if (draft != null && draft.remembered() != null && !draft.remembered().isBlank()
            && suitableDraft.test(draft.remembered())) {
            return draft.remembered();
        }
        String generated = generateSuggestion.get();
        rememberGenerated(tabSession, generated);
        return generated;
    }

    public void rememberDraft(TabSession tabSession, String text) {
        if (tabSession == null) {
            return;
        }
        if (text == null) {
            draftsByTab.remove(tabSession);
            return;
        }
        draftsByTab.put(tabSession, new Draft(text, null));
    }

    /** Submitting or cancelling an untouched suggestion does not turn it into a user choice. */
    public void rememberInput(TabSession tabSession, String text) {
        Draft draft = draftsByTab.get(tabSession);
        if (draft == null || !Objects.equals(text, draft.generated())) {
            rememberDraft(tabSession, text);
        }
    }

    /** Generated defaults are recalculated next time, without erasing a remembered user draft. */
    public void rememberGenerated(TabSession tabSession, String text) {
        if (tabSession == null) {
            return;
        }
        Draft draft = draftsByTab.get(tabSession);
        draftsByTab.put(tabSession, new Draft(draft == null ? null : draft.remembered(), text));
    }

    public void rememberSavedPath(TabSession tabSession, Path savedPath) {
        if (tabSession == null || savedPath == null) {
            return;
        }
        rememberDraft(tabSession, SavePathInput.format(savedPath.toAbsolutePath()));
    }

    public void remove(TabSession tabSession) {
        if (tabSession == null) {
            return;
        }
        draftsByTab.remove(tabSession);
    }
}
