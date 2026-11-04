package dev.tuink.editor.session.application;

import static org.junit.jupiter.api.Assertions.*;

import dev.tuink.editor.session.application.ApplicationCloseWorkflow.Choice;
import dev.tuink.editor.session.application.ApplicationCloseWorkflow.SaveProgress;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

/** Exit decisions use synthetic monotonic time and exact operation identities. */
class ApplicationCloseWorkflowTest {
    private final Recipient recipient = new Recipient("original");

    @Test
    void requestingQuitCancelsPreparationBeforeItCanConfirmOrWait() {
        var operation = operation(1);
        var requested = ApplicationCloseWorkflow.request(ApplicationCloseWorkflow.State.<Recipient, Operation>open(), recipient, operation);
        assertEquals(ApplicationCloseWorkflow.Phase.CANCELLING, requested.state().phase());
        assertEquals(ApplicationCloseWorkflow.Effect.CANCEL_PREPARATION, requested.effect());
        assertTrue(requested.state().running());
        assertNull(requested.state().waiting(), "the preparation's cancellation outcome decides whether waiting is needed");

        var prepared = ApplicationCloseWorkflow.prepared(requested.state(), operation,
            ApplicationCloseWorkflow.Policy.LIVE_RECOVERY, ApplicationCloseWorkflow.Continuation.RECHECK_DOCUMENTS, 100L, true);
        assertEquals(ApplicationCloseWorkflow.Phase.WAITING, prepared.state().phase());
        assertEquals(ApplicationCloseWorkflow.Effect.WAIT, prepared.effect());
        assertSame(operation, prepared.state().waiting().operation());
        assertEquals(ApplicationCloseWorkflow.Continuation.RECHECK_DOCUMENTS, prepared.state().waiting().continuation());
        assertEquals(100L + ApplicationCloseWorkflow.WAIT_NANOS, prepared.state().waiting().deadline());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void afterPreparationCancellationOnlyDirtyDocumentsRequireAQuestion(boolean confirmDirty) {
        var requested = ApplicationCloseWorkflow.request(ApplicationCloseWorkflow.State.<Recipient, Operation>open(), recipient, operation(1));
        var ready = ApplicationCloseWorkflow.prepared(requested.state(), null,
            ApplicationCloseWorkflow.Policy.LIVE_RECOVERY, ApplicationCloseWorkflow.Continuation.RECHECK_DOCUMENTS, 100L, confirmDirty);
        assertEquals(confirmDirty ? ApplicationCloseWorkflow.Phase.CONFIRMING : ApplicationCloseWorkflow.Phase.CLOSED,
            ready.state().phase());
        assertEquals(confirmDirty ? ApplicationCloseWorkflow.Effect.CONFIRM : ApplicationCloseWorkflow.Effect.EXIT,
            ready.effect());
        assertEquals(confirmDirty, ready.state().running());
        assertSame(recipient, ready.state().recipient());
    }

    @ParameterizedTest
    @EnumSource(Choice.class)
    void theWindowQuestionHasExplicitSaveDiscardAndCancelOutcomes(Choice choice) {
        var confirmed = ApplicationCloseWorkflow.choose(confirming(), choice);
        switch (choice) {
            case SAVE -> {
                assertEquals(ApplicationCloseWorkflow.Phase.SAVING, confirmed.state().phase());
                assertEquals(ApplicationCloseWorkflow.Effect.SAVE_ALL, confirmed.effect());
                assertTrue(confirmed.state().running());
            }
            case DISCARD -> {
                assertEquals(ApplicationCloseWorkflow.Phase.CLOSED, confirmed.state().phase());
                assertEquals(ApplicationCloseWorkflow.Effect.EXIT, confirmed.effect());
                assertFalse(confirmed.state().running());
            }
            case CANCEL -> {
                assertEquals(ApplicationCloseWorkflow.Phase.OPEN, confirmed.state().phase());
                assertEquals(ApplicationCloseWorkflow.Effect.NONE, confirmed.effect());
                assertTrue(confirmed.state().running());
            }
        }
    }

    @Test
    void saveAndDiscardCannotExitAWindowWithoutAnOutstandingQuestion() {
        var open = ApplicationCloseWorkflow.State.<Recipient, Operation>open();
        for (var choice : Choice.values()) {
            var decision = ApplicationCloseWorkflow.choose(open, choice);
            assertSame(open, decision.state());
            assertEquals(ApplicationCloseWorkflow.Effect.NONE, decision.effect());
        }
    }

    @ParameterizedTest
    @EnumSource(ApplicationCloseWorkflow.Policy.class)
    void repeatedQuitRetainsTheOperationDeadlineAndOriginalRecipient(ApplicationCloseWorkflow.Policy policy) {
        var operation = operation(1);
        var waiting = waiting(operation, policy, 100L);
        var otherRecipient = new Recipient("other");
        var repeated = ApplicationCloseWorkflow.request(waiting, otherRecipient, operation);
        assertSame(waiting, repeated.state());
        assertEquals(ApplicationCloseWorkflow.Effect.NONE, repeated.effect());
        assertSame(recipient, repeated.state().recipient());
        assertEquals(100L + ApplicationCloseWorkflow.WAIT_NANOS, repeated.state().waiting().deadline());
    }

    @ParameterizedTest
    @EnumSource(ApplicationCloseWorkflow.Policy.class)
    void theFiveSecondBoundaryPreservesDifferentLiveAndSnapshotTimeoutPolicies(ApplicationCloseWorkflow.Policy policy) {
        var operation = operation(1);
        var waiting = waiting(operation, policy, 100L);
        assertEquals(5_000_000_000L, ApplicationCloseWorkflow.WAIT_NANOS);
        var before = ApplicationCloseWorkflow.tick(waiting, operation, 5_000_000_099L);
        assertSame(waiting, before.state());
        assertEquals(ApplicationCloseWorkflow.Effect.NONE, before.effect());

        var expired = ApplicationCloseWorkflow.tick(waiting, operation, 5_000_000_100L);
        assertEquals(ApplicationCloseWorkflow.Phase.UNCERTAIN, expired.state().phase());
        assertEquals(policy == ApplicationCloseWorkflow.Policy.LIVE_RECOVERY
                ? ApplicationCloseWorkflow.Effect.EXPIRE_LIVE_SAVE : ApplicationCloseWorkflow.Effect.RETAIN_SNAPSHOT,
            expired.effect());
        assertSame(waiting.waiting(), expired.state().waiting());
        assertTrue(expired.state().running(), "only the coordinator applies the fatal live policy; snapshot timeout keeps the window open");
    }

    @ParameterizedTest
    @EnumSource(ApplicationCloseWorkflow.Outcome.class)
    void aLateSnapshotOutcomeDoesNotResumeItsExpiredQuit(ApplicationCloseWorkflow.Outcome outcome) {
        var operation = operation(1);
        var waiting = waiting(operation, ApplicationCloseWorkflow.Policy.SNAPSHOT_RETAIN, 100L);
        var uncertain = ApplicationCloseWorkflow.tick(waiting, operation, waiting.waiting().deadline()).state();
        var repeated = ApplicationCloseWorkflow.request(uncertain, recipient, operation);
        assertSame(uncertain, repeated.state(), "the same publication cannot acquire a fresh deadline");
        assertEquals(ApplicationCloseWorkflow.Effect.NONE, repeated.effect());
        assertSame(uncertain, ApplicationCloseWorkflow.tick(uncertain, operation, Long.MAX_VALUE).state());

        var completed = ApplicationCloseWorkflow.completed(uncertain, operation, outcome, false);
        assertEquals(ApplicationCloseWorkflow.Phase.OPEN, completed.state().phase());
        assertEquals(ApplicationCloseWorkflow.Effect.NONE, completed.effect());
        assertTrue(completed.state().running());
        assertNull(completed.state().waiting());
    }

    @Test
    void anotherOperationWithTheSameCounterCannotCompleteOrExpireTheWait() {
        var operation = operation(1);
        var unrelated = operation(1);
        assertEquals(operation, unrelated, "equal operation values still represent distinct publications");
        var waiting = waiting(operation, ApplicationCloseWorkflow.Policy.LIVE_RECOVERY, 100L);
        var completed = ApplicationCloseWorkflow.completed(waiting, unrelated, ApplicationCloseWorkflow.Outcome.SAVED, false);
        assertSame(waiting, completed.state());
        assertEquals(ApplicationCloseWorkflow.Effect.NONE, completed.effect());
        var expired = ApplicationCloseWorkflow.tick(waiting, unrelated, waiting.waiting().deadline());
        assertSame(waiting, expired.state());
        assertEquals(ApplicationCloseWorkflow.Effect.NONE, expired.effect());
    }

    @Test
    void saveAllStartsItsDeadlineOnlyAtPublicationAndTheNextSaveGetsANewBound() {
        var first = operation(1);
        var second = operation(2);
        var saving = ApplicationCloseWorkflow.choose(confirming(), Choice.SAVE).state();
        assertSame(saving, ApplicationCloseWorkflow.publishing(saving, null, 50L).state());
        assertNull(saving.waiting(), "filename choice or preparation has not crossed publication");
        var waiting = ApplicationCloseWorkflow.publishing(saving, first, 100L).state();
        assertEquals(ApplicationCloseWorkflow.Continuation.SAVE_ALL, waiting.waiting().continuation());
        assertEquals(ApplicationCloseWorkflow.Policy.LIVE_RECOVERY, waiting.waiting().policy());

        // The ready receipt is applied before the controller evaluates a deadline.
        var receipt = ApplicationCloseWorkflow.completed(waiting, first, ApplicationCloseWorkflow.Outcome.SAVED, false);
        assertEquals(ApplicationCloseWorkflow.Phase.SAVING, receipt.state().phase());
        assertEquals(ApplicationCloseWorkflow.Effect.NONE, receipt.effect());
        assertNull(receipt.state().waiting());
        var next = ApplicationCloseWorkflow.publishing(receipt.state(), second, 5_000_000_100L).state();
        assertSame(second, next.waiting().operation());
        assertEquals(10_000_000_100L, next.waiting().deadline());
        assertSame(next, ApplicationCloseWorkflow.tick(next, second, waiting.waiting().deadline()).state());
        assertSame(next, ApplicationCloseWorkflow.tick(next, first, next.waiting().deadline()).state());
        assertSame(next, ApplicationCloseWorkflow.completed(next, first, ApplicationCloseWorkflow.Outcome.SAVED, false).state(),
            "an old callback cannot clear a replacement save bound during its continuation");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void aConfirmedStandaloneReceiptRechecksTheCurrentDirtyDocuments(boolean confirmDirty) {
        var operation = operation(1);
        var waiting = waiting(operation, ApplicationCloseWorkflow.Policy.LIVE_RECOVERY, 100L);
        var completed = ApplicationCloseWorkflow.completed(waiting, operation, ApplicationCloseWorkflow.Outcome.SAVED, confirmDirty);
        assertEquals(confirmDirty ? ApplicationCloseWorkflow.Phase.CONFIRMING : ApplicationCloseWorkflow.Phase.CLOSED,
            completed.state().phase());
        assertEquals(confirmDirty ? ApplicationCloseWorkflow.Effect.CONFIRM : ApplicationCloseWorkflow.Effect.EXIT,
            completed.effect());
    }

    @ParameterizedTest
    @EnumSource(value = ApplicationCloseWorkflow.Outcome.class, names = {"FAILED", "CANCELLED"})
    void anUnsuccessfulPublicationCancelsDeferredQuit(ApplicationCloseWorkflow.Outcome outcome) {
        var operation = operation(1);
        var waiting = waiting(operation, ApplicationCloseWorkflow.Policy.LIVE_RECOVERY, 100L);
        var completed = ApplicationCloseWorkflow.completed(waiting, operation, outcome, false);
        assertEquals(ApplicationCloseWorkflow.Phase.OPEN, completed.state().phase());
        assertEquals(ApplicationCloseWorkflow.Effect.NONE, completed.effect());
        assertTrue(completed.state().running());
    }

    @Test
    void cancellingAQuitFilenameContinuationLeavesNoLatentCloseIntent() {
        var saving = ApplicationCloseWorkflow.choose(confirming(), Choice.SAVE).state();
        var filename = ApplicationCloseWorkflow.saveProgress(saving, recipient, SaveProgress.CONTINUE);
        assertEquals(ApplicationCloseWorkflow.Phase.SAVING, filename.phase());
        var cancelled = ApplicationCloseWorkflow.saveProgress(filename, recipient, SaveProgress.CANCELLED);
        assertEquals(ApplicationCloseWorkflow.Phase.OPEN, cancelled.phase());
        assertNull(cancelled.waiting());
        assertTrue(cancelled.running());
        assertSame(cancelled, ApplicationCloseWorkflow.completed(cancelled, operation(1), ApplicationCloseWorkflow.Outcome.SAVED, false).state());
    }

    @Test
    void aSaveWorkflowRetryQuestionAndFinalExitKeepTheirWindowRecipient() {
        var saving = ApplicationCloseWorkflow.choose(confirming(), Choice.SAVE).state();
        var other = new Recipient("other");
        var retry = ApplicationCloseWorkflow.saveProgress(saving, other, SaveProgress.CONFIRM);
        assertEquals(ApplicationCloseWorkflow.Phase.CONFIRMING, retry.phase());
        assertSame(recipient, retry.recipient());
        var exited = ApplicationCloseWorkflow.saveProgress(saving, other, SaveProgress.FINISHED);
        assertEquals(ApplicationCloseWorkflow.Phase.CLOSED, exited.phase());
        assertSame(recipient, exited.recipient());
        assertFalse(exited.running());
        assertSame(exited, ApplicationCloseWorkflow.request(exited, other, null).state());
        assertSame(exited, ApplicationCloseWorkflow.cancel(exited));
    }

    @Test
    void deadlineArithmeticWorksAcrossTheMonotonicClockWraparound() {
        var operation = operation(1);
        long started = Long.MAX_VALUE - ApplicationCloseWorkflow.WAIT_NANOS / 2;
        var waiting = waiting(operation, ApplicationCloseWorkflow.Policy.SNAPSHOT_RETAIN, started);
        assertSame(waiting, ApplicationCloseWorkflow.tick(waiting, operation, waiting.waiting().deadline() - 1).state());
        assertEquals(ApplicationCloseWorkflow.Effect.RETAIN_SNAPSHOT,
            ApplicationCloseWorkflow.tick(waiting, operation, waiting.waiting().deadline()).effect());
    }

    @Test
    void repeatedQuitKeepsTheSaveAllContinuationWhenPublicationWasFirstObservedByTheRequest() {
        var operation = operation(1);
        var saving = ApplicationCloseWorkflow.choose(confirming(), Choice.SAVE).state();
        var repeated = ApplicationCloseWorkflow.request(saving, recipient, operation);
        var waiting = ApplicationCloseWorkflow.prepared(repeated.state(), operation, ApplicationCloseWorkflow.Policy.LIVE_RECOVERY,
            ApplicationCloseWorkflow.Continuation.SAVE_ALL, 100L, false).state();
        var completed = ApplicationCloseWorkflow.completed(waiting, operation, ApplicationCloseWorkflow.Outcome.SAVED, false);
        assertEquals(ApplicationCloseWorkflow.Phase.SAVING, completed.state().phase());
        assertTrue(completed.state().running(), "the dirty decision stays with the authorized Save-All continuation");
        assertEquals(ApplicationCloseWorkflow.Effect.NONE, completed.effect());
    }

    private ApplicationCloseWorkflow.State<Recipient, Operation> confirming() {
        var request = ApplicationCloseWorkflow.request(ApplicationCloseWorkflow.State.<Recipient, Operation>open(), recipient, null);
        return ApplicationCloseWorkflow.prepared(request.state(), null,
            ApplicationCloseWorkflow.Policy.LIVE_RECOVERY, ApplicationCloseWorkflow.Continuation.RECHECK_DOCUMENTS, 0L, true).state();
    }

    private ApplicationCloseWorkflow.State<Recipient, Operation> waiting(Operation operation,
        ApplicationCloseWorkflow.Policy policy, long now) {
        var request = ApplicationCloseWorkflow.request(ApplicationCloseWorkflow.State.<Recipient, Operation>open(), recipient, operation);
        return ApplicationCloseWorkflow.prepared(request.state(), operation, policy, ApplicationCloseWorkflow.Continuation.RECHECK_DOCUMENTS, now, false).state();
    }

    private static Operation operation(long sequence) {
        return new Operation(sequence);
    }

    private record Recipient(String name) { }
    private record Operation(long sequence) { }
}
