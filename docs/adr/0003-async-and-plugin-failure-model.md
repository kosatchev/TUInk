# ADR 0003: asynchronous ingress and plugin failure model

- Status: accepted
- Date: 2026-08-24

## Context

Log streams, diagnostics, completion, and plugin providers produce results away
from the editor mutation thread. Deadlines alone cannot isolate arbitrary
in-process Java code from `System.exit`, OOM, leaked threads, or code that
ignores interruption. An unbounded event bus would also make ordering,
backpressure, and shutdown accidental.

## Decision

In-process plugins are trusted process-level code. Their provider callbacks
never execute on the session mutation thread. The host uses bounded executors
and a bounded external-event queue; overload is reported explicitly rather than
blocking or silently dropping work.

Accepted external events retain FIFO ingress order. An empty-to-nonempty
transition requests a wakeup; additional offers while the queue remains
non-empty never request another one. If the session drains the queue and a
producer refills it while the preceding wakeup callback is still running, that
real refill transition produces one serialized follow-up callback. A failed
callback leaves the accepted event queued and requires an explicit retry. The
session drains a bounded batch per turn and schedules another turn itself when
the batch leaves queued work, so terminal input and resize remain fair. Closing
ingress rejects new work while allowing an orderly drain; abort shutdown
explicitly discards pending events.

Every document-derived result carries `DocumentId` and source revision. The
session rejects foreign or stale results before applying them. Cancellation is
cooperative. A timeout means the host stops awaiting and applying the result;
it does not claim to kill arbitrary Java code. Provider exceptions disable or
degrade that provider without mutating editor state.

Hard isolation requires an out-of-process, versioned protocol. Dynamic
in-process loading must never be documented as a sandbox.

### Document I/O and recovery (2026-10-27 amendment)

Each `EditorDocumentSession` retains one `DocumentIoCoordinator`. The owner
captures immutable save inputs; the host runs its one-shot `Work.perform()` on
an I/O worker and returns the opaque completion to the same coordinator. A
current save enters `WRITING`; Save As passes through `INSPECTING`,
`AWAITING_APPROVAL`, then `WRITING`. Approval retains the exact provider target
and version. The host decides whether the observed overwrite needs a user
prompt. Operation IDs and private provenance reject foreign results; stale or
duplicate replies cannot advance another operation. Required owner outcomes
are prepared before backend savepoint publication.

All save phases lock document mutations through the session, including other
registered views and workspace edits. Navigation, selection, copy, and search
navigation remain available. The public session API exposes the coordinator;
raw save captures are implementation details. `PreparedDocumentOpen` separately
performs one detached read and admits only its exact result on the owner; the
host retains workspace alias checks and document/view publication.

Cancellation of inspection or approval drops late replies. Cancelling an
unstarted write atomically prevents its execution; once a worker claims a write,
the owner must collect its outcome. `stop()` prevents new operations and retains
claimed writes for host draining. Scoped cancellation binds a dialog or request to its still-current
phase, so an old prompt cannot cancel a later save. A worker failure distinguishes
`FAILED_BEFORE_COMMIT` from `UNKNOWN`. `CONFIRMED_NOT_PUBLISHED` reports a confirmed
external write whose savepoint could not be admitted. Unconfirmed outcomes retain
the draft and do not publish a successful savepoint or authorize recovery deletion.

`RecoveryCoordinator` owns entry IDs and cleanup permission through neutral
participant/persistence ports. The owner captures immutable inputs; background
materialization and persistence use the host worker, one checkpoint at a time. Lifetime checkpoint
requests also capture view-only changes; a blocked write does not accumulate a
queue of document snapshots. Even the first uncertain checkpoint publication
retains entry ownership for later cleanup. Restored ownership is prepared, then
published only after the frontend publishes its documents; abandoned preparations
grant no deletion permission. Startup delete/release cannot act on an entry owned
by an active document or outstanding operation.

View detach grants no discard permission. Hosts keep the document participant
registered across temporary detach and request a checkpoint before suspend.
Removing a document with `PRESERVE` retains its persisted entries; `DISCARD`
explicitly permits deletion. On shutdown the host first drains document writes.
An unconfirmed stop attempts a final checkpoint despite debounce; confirmed exit
permits cleanup. Finalization attempts the remaining documents after a runtime or
memory failure, then propagates that failure. Executors and their shutdown, UI
prompts, viewport serialization, and platform lifecycle remain host responsibilities.

## Consequences

- Ordering, overload, wakeup, fairness, and shutdown have testable contracts.
- Provider APIs require a deadline and cancellation token, but implementations
  must tolerate non-cooperative tasks surviving until process termination.
- The plugin-host failure contract means editor-state and session-thread isolation for
  ordinary timeout/exception/stale-result cases, not process containment.
