package dev.tuink.tui;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.tuink.editor.storage.StorageOperation;
import org.junit.jupiter.api.Test;

class SaveProgressPresentationTest {
    @Test
    void smallChecksShowExactBytesAndTheCancellationControl() {
        assertEquals("Checking save target 6/6 B · Esc to cancel", SaveProgressPresentation.message(
            new StorageOperation.Progress(StorageOperation.Phase.INSPECTING, 6, 6, true), true));
    }

    @Test
    void aFinishedPartialUnitDoesNotAppearIncomplete() {
        assertEquals("Preparing save 2/2 KiB · Esc to cancel", SaveProgressPresentation.message(
            new StorageOperation.Progress(StorageOperation.Phase.STAGING, 1537, 1537, true), true));
    }

    @Test
    void portablePublicationStillShowsProgressWithoutOfferingCancellation() {
        assertEquals("Finishing save 1/2 MiB…", SaveProgressPresentation.message(
            new StorageOperation.Progress(StorageOperation.Phase.PUBLISHING, 1_048_576L, 2_097_152L, false), true));
    }
}
