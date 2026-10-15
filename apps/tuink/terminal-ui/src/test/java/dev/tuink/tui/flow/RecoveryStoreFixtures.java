package dev.tuink.tui.flow;

import java.nio.file.Path;

/** Test access to isolated storage without changing user.home or production API. */
public final class RecoveryStoreFixtures {
    private RecoveryStoreFixtures() {}

    public static RecoveryStore open(Path directory) {
        return new RecoveryStore(directory);
    }
}
