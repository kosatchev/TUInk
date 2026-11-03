package dev.tuink.tui;

import dev.tuink.editor.document.PresentationId;

/** Explicit capabilities for isolated menu/dispatcher tests without a workspace. */
final class TestSurfaces {
    private TestSurfaces() {}

    static ActiveSurface source(boolean reading) {
        return new ActiveSurface(new PresentationId("isolated-source"), ActiveSurface.Kind.SOURCE,
            ActiveSurface.Focus.CONTENT, ActiveSurface.ModalScope.NONE, reading, false, false, true, true, false);
    }

    static ActiveSurface source(UiSessionState preferences) {
        return new ActiveSurface(new PresentationId("isolated-source"), ActiveSurface.Kind.SOURCE,
            ActiveSurface.Focus.CONTENT, ActiveSurface.ModalScope.NONE, preferences.readingModeSetting,
            false, false, true, true, preferences.wrapLinesSetting);
    }
}
