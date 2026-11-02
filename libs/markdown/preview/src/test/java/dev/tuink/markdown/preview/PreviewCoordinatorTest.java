package dev.tuink.markdown.preview;

import dev.tuink.editor.document.DocumentId;
import dev.tuink.editor.document.DocumentSnapshot;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PreviewCoordinatorTest {
    @Test void refusesOldGenerationForeignRevisionAndClosedLifetime() {
        var id = new DocumentId("document");
        var snapshot = new DocumentSnapshot(id, 2, List.of("draft"));
        var gate = new PreviewCoordinator();
        var first = gate.prepare(snapshot);
        var next = gate.prepare(snapshot);
        assertFalse(gate.accepts(first, id, 2));
        assertTrue(gate.accepts(next, id, 2));
        assertFalse(gate.accepts(next, id, 3));
        assertFalse(gate.accepts(next, new DocumentId("foreign"), 2));
        gate.close();
        assertFalse(gate.accepts(next, id, 2));
        assertThrows(IllegalStateException.class, () -> gate.prepare(snapshot));
    }
    @Test void rejectsForeignThreadWithoutReadingMutableState() throws Exception {
        var gate = new PreviewCoordinator();
        var caught = new AtomicReference<Throwable>();
        Thread thread = new Thread(() -> { try { gate.cancel(); } catch (Throwable failure) { caught.set(failure); } });
        thread.start(); thread.join();
        assertInstanceOf(IllegalStateException.class, caught.get());
        gate.close();
    }
}
