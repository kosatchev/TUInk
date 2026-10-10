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

## Consequences

- Ordering, overload, wakeup, fairness, and shutdown have testable contracts.
- Provider APIs require a deadline and cancellation token, but implementations
  must tolerate non-cooperative tasks surviving until process termination.
- The plugin-host failure contract means editor-state and session-thread isolation for
  ordinary timeout/exception/stale-result cases, not process containment.
