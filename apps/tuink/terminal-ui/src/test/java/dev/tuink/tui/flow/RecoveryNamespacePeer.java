package dev.tuink.tui.flow;

import dev.tuink.core.RecoverySnapshot;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Separate-process fixture for namespace lifetime and entry ownership checks. */
public final class RecoveryNamespacePeer {
    private RecoveryNamespacePeer() { }

    public static void main(String[] args) throws Exception {
        try (RecoveryStore store = new RecoveryStore(Path.of(args[0]))) {
            store.save("peer", new RecoverySnapshot(List.of("peer"), null, null, null, true, null,
                null, null, null, null));
            Files.writeString(Path.of(args[1]), "ready");
            if (System.in.read() < 0) throw new IllegalStateException("test peer was not released");
        }
    }
}
