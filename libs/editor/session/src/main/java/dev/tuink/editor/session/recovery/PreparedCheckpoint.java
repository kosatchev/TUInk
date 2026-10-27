package dev.tuink.editor.session.recovery;

import java.io.IOException;

/** An immutable owner capture; materialization may run on the host's I/O worker. */
@FunctionalInterface
public interface PreparedCheckpoint<C> {
    C materialize() throws IOException;
}
