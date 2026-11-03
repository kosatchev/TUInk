package dev.tuink.tui;

import static org.junit.jupiter.api.Assertions.*;

import dev.tuink.tui.flow.ConfirmChoice;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

/** Exit decisions use synthetic monotonic time and exact operation identities. */
class WindowCloseWorkflowTest {
    private final UiRecipient recipient = UiRecipient.window(new TabUiState(), null);

    @Test
    void requestingQuitCancelsPreparationBeforeItCanConfirmOrWait() {
        var operation = operation(1);
        var requested = WindowCloseWorkflow.request(WindowCloseWorkflow.State.open(), recipient, operation);
        assertEquals(WindowCloseWorkflow.Phase.CANCELLING, requested.state().phase());
        assertEquals(WindowCloseWorkflow.Effect.CANCEL_PREPARATION, requested.effect());
        assertTrue(requested.state().running());
        assertNull(requested.state().waiting(), "the preparation's cancellation outcome decides whether waiting is needed");

        var prepared = WindowCloseWorkflow.prepared(requested.state(), operation,
            WindowCloseWorkflow.Policy.LIVE_RECOVERY, WindowCloseWorkflow.Continuation.RECHECK_DOCUMENTS, 100L, true);
        assertEquals(WindowCloseWorkflow.Phase.WAITING, prepared.state().phase());
        assertEquals(WindowCloseWorkflow.Effect.WAIT, prepared.effect());
        assertSame(operation, prepared.state().waiting().operation());
        assertEquals(WindowCloseWorkflow.Continuation.RECHECK_DOCUMENTS, prepared.state().waiting().continuation());
        assertEquals(100L + WindowCloseWorkflow.WAIT_NANOS, prepared.state().waiting().deadline());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void afterPreparationCancellationOnlyDirtyDocumentsRequireAQuestion(boolean confirmDirty) {
        var requested = WindowCloseWorkflow.request(WindowCloseWorkflow.State.open(), recipient, operation(1));
        var ready = WindowCloseWorkflow.prepared(requested.state(), null,
            WindowCloseWorkflow.Policy.LIVE_RECOVERY, WindowCloseWorkflow.Continuation.RECHECK_DOCUMENTS, 100L, confirmDirty);
        assertEquals(confirmDirty ? WindowCloseWorkflow.Phase.CONFIRMING : WindowCloseWorkflow.Phase.CLOSED,
            ready.state().phase());
        assertEquals(confirmDirty ? WindowCloseWorkflow.Effect.CONFIRM : WindowCloseWorkflow.Effect.EXIT,
            ready.effect());
        assertEquals(confirmDirty, ready.state().running());
        assertSame(recipient, ready.state().recipient());
    }

    @ParameterizedTest
    @EnumSource(ConfirmChoice.class)
    void theWindowQuestionHasExplicitSaveDiscardAndCancelOutcomes(ConfirmChoice choice) {
        var confirmed = WindowCloseWorkflow.choose(confirming(), choice);
        switch (choice) {
            case SAVE -> {
                assertEquals(WindowCloseWorkflow.Phase.SAVING, confirmed.state().phase());
                assertEquals(WindowCloseWorkflow.Effect.SAVE_ALL, confirmed.effect());
                assertTrue(confirmed.state().running());
            }
            case DISCARD -> {
                assertEquals(WindowCloseWorkflow.Phase.CLOSED, confirmed.state().phase());
                assertEquals(WindowCloseWorkflow.Effect.EXIT, confirmed.effect());
                assertFalse(confirmed.state().running());
            }
            case CANCEL -> {
                assertEquals(WindowCloseWorkflow.Phase.OPEN, confirmed.state().phase());
                assertEquals(WindowCloseWorkflow.Effect.NONE, confirmed.effect());
                assertTrue(confirmed.state().running());
            }
        }
    }

    @Test
    void saveAndDiscardCannotExitAWindowWithoutAnOutstandingQuestion() {
        var open = WindowCloseWorkflow.State.open();
        for (var choice : ConfirmChoice.values()) {
            var decision = WindowCloseWorkflow.choose(open, choice);
            assertSame(open, decision.state());
            assertEquals(WindowCloseWorkflow.Effect.NONE, decision.effect());
        }
    }

    @ParameterizedTest
    @EnumSource(WindowCloseWorkflow.Policy.class)
    void repeatedQuitRetainsTheOperationDeadlineAndOriginalRecipient(WindowCloseWorkflow.Policy policy) {
        var operation = operation(1);
        var waiting = waiting(operation, policy, 100L);
        var otherRecipient = UiRecipient.window(new TabUiState(), null);
        var repeated = WindowCloseWorkflow.request(waiting, otherRecipient, operation);
        assertSame(waiting, repeated.state());
        assertEquals(WindowCloseWorkflow.Effect.NONE, repeated.effect());
        assertSame(recipient, repeated.state().recipient());
        assertEquals(100L + WindowCloseWorkflow.WAIT_NANOS, repeated.state().waiting().deadline());
    }

    @ParameterizedTest
    @EnumSource(WindowCloseWorkflow.Policy.class)
    void theFiveSecondBoundaryPreservesDifferentLiveAndSnapshotTimeoutPolicies(WindowCloseWorkflow.Policy policy) {
        var operation = operation(1);
        var waiting = waiting(operation, policy, 100L);
        assertEquals(5_000_000_000L, WindowCloseWorkflow.WAIT_NANOS);
        var before = WindowCloseWorkflow.tick(waiting, operation, 5_000_000_099L);
        assertSame(waiting, before.state());
        assertEquals(WindowCloseWorkflow.Effect.NONE, before.effect());

        var expired = WindowCloseWorkflow.tick(waiting, operation, 5_000_000_100L);
        assertEquals(WindowCloseWorkflow.Phase.UNCERTAIN, expired.state().phase());
        assertEquals(policy == WindowCloseWorkflow.Policy.LIVE_RECOVERY
                ? WindowCloseWorkflow.Effect.EXPIRE_LIVE_SAVE : WindowCloseWorkflow.Effect.RETAIN_SNAPSHOT,
            expired.effect());
        assertSame(waiting.waiting(), expired.state().waiting());
        assertTrue(expired.state().running(), "only the coordinator applies the fatal live policy; snapshot timeout keeps the window open");
    }

    @ParameterizedTest
    @EnumSource(WindowCloseWorkflow.Outcome.class)
    void aLateSnapshotOutcomeDoesNotResumeItsExpiredQuit(WindowCloseWorkflow.Outcome outcome) {
        var operation = operation(1);
        var waiting = waiting(operation, WindowCloseWorkflow.Policy.SNAPSHOT_RETAIN, 100L);
        var uncertain = WindowCloseWorkflow.tick(waiting, operation, waiting.waiting().deadline()).state();
        var repeated = WindowCloseWorkflow.request(uncertain, recipient, operation);
        assertSame(uncertain, repeated.state(), "the same publication cannot acquire a fresh deadline");
        assertEquals(WindowCloseWorkflow.Effect.NONE, repeated.effect());
        assertSame(uncertain, WindowCloseWorkflow.tick(uncertain, operation, Long.MAX_VALUE).state());

        var completed = WindowCloseWorkflow.completed(uncertain, operation, outcome, false);
        assertEquals(WindowCloseWorkflow.Phase.OPEN, completed.state().phase());
        assertEquals(WindowCloseWorkflow.Effect.NONE, completed.effect());
        assertTrue(completed.state().running());
        assertNull(completed.state().waiting());
    }

    @Test
    void anotherOperationWithTheSameCounterCannotCompleteOrExpireTheWait() {
        var operation = operation(1);
        var unrelated = operation(1);
        var waiting = waiting(operation, WindowCloseWorkflow.Policy.LIVE_RECOVERY, 100L);
        var completed = WindowCloseWorkflow.completed(waiting, unrelated, WindowCloseWorkflow.Outcome.SAVED, false);
        assertSame(waiting, completed.state());
        assertEquals(WindowCloseWorkflow.Effect.NONE, completed.effect());
        var expired = WindowCloseWorkflow.tick(waiting, unrelated, waiting.waiting().deadline());
        assertSame(waiting, expired.state());
        assertEquals(WindowCloseWorkflow.Effect.NONE, expired.effect());
    }

    @Test
    void saveAllStartsItsDeadlineOnlyAtPublicationAndTheNextSaveGetsANewBound() {
        var first = operation(1);
        var second = operation(2);
        var saving = WindowCloseWorkflow.choose(confirming(), ConfirmChoice.SAVE).state();
        assertSame(saving, WindowCloseWorkflow.publishing(saving, null, 50L).state());
        assertNull(saving.waiting(), "filename choice or preparation has not crossed publication");
        var waiting = WindowCloseWorkflow.publishing(saving, first, 100L).state();
        assertEquals(WindowCloseWorkflow.Continuation.SAVE_ALL, waiting.waiting().continuation());
        assertEquals(WindowCloseWorkflow.Policy.LIVE_RECOVERY, waiting.waiting().policy());

        // The ready receipt is applied before the controller evaluates a deadline.
        var receipt = WindowCloseWorkflow.completed(waiting, first, WindowCloseWorkflow.Outcome.SAVED, false);
        assertEquals(WindowCloseWorkflow.Phase.SAVING, receipt.state().phase());
        assertEquals(WindowCloseWorkflow.Effect.NONE, receipt.effect());
        assertNull(receipt.state().waiting());
        var next = WindowCloseWorkflow.publishing(receipt.state(), second, 5_000_000_100L).state();
        assertSame(second, next.waiting().operation());
        assertEquals(10_000_000_100L, next.waiting().deadline());
        assertSame(next, WindowCloseWorkflow.tick(next, second, waiting.waiting().deadline()).state());
        assertSame(next, WindowCloseWorkflow.tick(next, first, next.waiting().deadline()).state());
        assertSame(next, WindowCloseWorkflow.completed(next, first, WindowCloseWorkflow.Outcome.SAVED, false).state(),
            "an old callback cannot clear a replacement save bound during its continuation");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void aConfirmedStandaloneReceiptRechecksTheCurrentDirtyDocuments(boolean confirmDirty) {
        var operation = operation(1);
        var waiting = waiting(operation, WindowCloseWorkflow.Policy.LIVE_RECOVERY, 100L);
        var completed = WindowCloseWorkflow.completed(waiting, operation, WindowCloseWorkflow.Outcome.SAVED, confirmDirty);
        assertEquals(confirmDirty ? WindowCloseWorkflow.Phase.CONFIRMING : WindowCloseWorkflow.Phase.CLOSED,
            completed.state().phase());
        assertEquals(confirmDirty ? WindowCloseWorkflow.Effect.CONFIRM : WindowCloseWorkflow.Effect.EXIT,
            completed.effect());
    }

    @ParameterizedTest
    @EnumSource(value = WindowCloseWorkflow.Outcome.class, names = {"FAILED", "CANCELLED"})
    void anUnsuccessfulPublicationCancelsDeferredQuit(WindowCloseWorkflow.Outcome outcome) {
        var operation = operation(1);
        var waiting = waiting(operation, WindowCloseWorkflow.Policy.LIVE_RECOVERY, 100L);
        var completed = WindowCloseWorkflow.completed(waiting, operation, outcome, false);
        assertEquals(WindowCloseWorkflow.Phase.OPEN, completed.state().phase());
        assertEquals(WindowCloseWorkflow.Effect.NONE, completed.effect());
        assertTrue(completed.state().running());
    }

    @Test
    void cancellingAQuitFilenameContinuationLeavesNoLatentCloseIntent() {
        var saving = WindowCloseWorkflow.choose(confirming(), ConfirmChoice.SAVE).state();
        var filename = WindowCloseWorkflow.saveResult(saving, recipient, true, false, true);
        assertEquals(WindowCloseWorkflow.Phase.SAVING, filename.phase());
        var cancelled = WindowCloseWorkflow.saveResult(filename, recipient, true, false, false);
        assertEquals(WindowCloseWorkflow.Phase.OPEN, cancelled.phase());
        assertNull(cancelled.waiting());
        assertTrue(cancelled.running());
        assertSame(cancelled, WindowCloseWorkflow.completed(cancelled, operation(1), WindowCloseWorkflow.Outcome.SAVED, false).state());
    }

    @Test
    void aSaveWorkflowRetryQuestionAndFinalExitKeepTheirWindowRecipient() {
        var saving = WindowCloseWorkflow.choose(confirming(), ConfirmChoice.SAVE).state();
        var other = UiRecipient.window(new TabUiState(), null);
        var retry = WindowCloseWorkflow.saveResult(saving, other, true, true, false);
        assertEquals(WindowCloseWorkflow.Phase.CONFIRMING, retry.phase());
        assertSame(recipient, retry.recipient());
        var exited = WindowCloseWorkflow.saveResult(saving, other, false, false, false);
        assertEquals(WindowCloseWorkflow.Phase.CLOSED, exited.phase());
        assertSame(recipient, exited.recipient());
        assertFalse(exited.running());
        assertSame(exited, WindowCloseWorkflow.request(exited, other, null).state());
        assertSame(exited, WindowCloseWorkflow.cancel(exited));
    }

    @Test
    void deadlineArithmeticWorksAcrossTheMonotonicClockWraparound() {
        var operation = operation(1);
        long started = Long.MAX_VALUE - WindowCloseWorkflow.WAIT_NANOS / 2;
        var waiting = waiting(operation, WindowCloseWorkflow.Policy.SNAPSHOT_RETAIN, started);
        assertSame(waiting, WindowCloseWorkflow.tick(waiting, operation, waiting.waiting().deadline() - 1).state());
        assertEquals(WindowCloseWorkflow.Effect.RETAIN_SNAPSHOT,
            WindowCloseWorkflow.tick(waiting, operation, waiting.waiting().deadline()).effect());
    }

    @Test
    void repeatedQuitKeepsTheSaveAllContinuationWhenPublicationWasFirstObservedByTheRequest() {
        var operation = operation(1);
        var saving = WindowCloseWorkflow.choose(confirming(), ConfirmChoice.SAVE).state();
        var repeated = WindowCloseWorkflow.request(saving, recipient, operation);
        var waiting = WindowCloseWorkflow.prepared(repeated.state(), operation, WindowCloseWorkflow.Policy.LIVE_RECOVERY,
            WindowCloseWorkflow.Continuation.SAVE_ALL, 100L, false).state();
        var completed = WindowCloseWorkflow.completed(waiting, operation, WindowCloseWorkflow.Outcome.SAVED, false);
        assertEquals(WindowCloseWorkflow.Phase.SAVING, completed.state().phase());
        assertTrue(completed.state().running(), "the dirty decision stays with the authorized Save-All continuation");
        assertEquals(WindowCloseWorkflow.Effect.NONE, completed.effect());
    }

    private WindowCloseWorkflow.State confirming() {
        var request = WindowCloseWorkflow.request(WindowCloseWorkflow.State.open(), recipient, null);
        return WindowCloseWorkflow.prepared(request.state(), null,
            WindowCloseWorkflow.Policy.LIVE_RECOVERY, WindowCloseWorkflow.Continuation.RECHECK_DOCUMENTS, 0L, true).state();
    }

    private WindowCloseWorkflow.State waiting(DocumentIoController.Operation operation,
        WindowCloseWorkflow.Policy policy, long now) {
        var request = WindowCloseWorkflow.request(WindowCloseWorkflow.State.open(), recipient, operation);
        return WindowCloseWorkflow.prepared(request.state(), operation, policy, WindowCloseWorkflow.Continuation.RECHECK_DOCUMENTS, now, false).state();
    }

    private static DocumentIoController.Operation operation(long sequence) {
        return new DocumentIoController.Operation(sequence, DocumentIoController.Kind.SAVE);
    }
}
