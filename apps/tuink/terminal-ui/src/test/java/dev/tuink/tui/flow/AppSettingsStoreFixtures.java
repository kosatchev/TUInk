package dev.tuink.tui.flow;

import java.io.IOException;
import java.nio.file.Path;

/** Isolated settings paths for frontend integration tests without changing the user's profile. */
public final class AppSettingsStoreFixtures {
    private AppSettingsStoreFixtures() {}

    public static AppSettingsStore.LoadedSettings loadSnapshot(Path path) throws IOException {
        return AppSettingsStore.loadSnapshot(path);
    }

    public static void save(Path path, AppSettings settings) throws IOException {
        AppSettingsStore.save(path, settings);
    }
}
