# ADR 0001: ownership of editor contracts and command dispatch

- Status: accepted
- Date: 2026-08-24

## Context

Command values originally lived in `dev.tuink.tui.command`, although keyboard,
menus, macros, headless sessions, and plugins are expected to share them. The
roadmap also introduced document snapshots and workspace edits in more than one
future module without fixing their coordinate system or canonical owner.

Leaving these values in the frontend would force `editor-session` or
`plugin-api` to depend on TUI code. Duplicating them would create conversion
paths with subtly different revision and range semantics.

## Decision

`editor-contract` owns the presentation-independent immutable values:

- `CommandId`, `CommandInvocation`, `CommandDescriptor`, and argument schemas;
- `DocumentId`, `DocumentSnapshot`, `TextPosition`, `TextRange`, `TextEdit`, and
  `WorkspaceEdit`.

`editor-session` owns the reusable command registry and document execution
boundary: workspace edit validation, revision gates, and external-event ingress.
The TUInk frontend owns keymaps, built-in presentation catalogs, focus-based
receiver selection, application/file operations, and view commands. A future
`plugin-api` may depend on `editor-contract`; it must not redefine these DTOs or
depend on `editor-session` implementation.

Command identity does not determine its receiver: Copy or Undo may address a
focused control or the document. The frontend selects that receiver before
execution. TUIframe owns local control behavior without depending on TUInk
command identifiers. Keyboard and menu commands must converge on the same
receiver and execution path. The execution result, including rejection and
view effects, governs cursor following; viewport code must not predict success
from a second list of command identifiers.

Command arguments remain serialized strings so invocations are recordable and
transportable. Every registered command declares a schema. The session rejects
unknown, missing, and ill-typed arguments before invoking a handler.

Text coordinates are zero-based `(line, UTF-16 offset)` pairs. Ranges are
half-open. A `WorkspaceEdit` targets one document revision, is canonicalized in
ascending range order, and rejects overlaps and ambiguous shared insertion
points. The host validates all ranges and surrogate boundaries before mutation;
application is atomic and proceeds in reverse range order.

Mutable document state is owned by an `EditorDocumentSession` bound to its
creating thread. Background consumers receive only immutable snapshots. A
workspace edit submitted from another thread, for another document, for a
stale revision, or with an invalid range is rejected before core state is read
or changed. Accepted batches cross one narrow core replacement boundary and
produce one undo entry.

Both modules are internal/incubating until plugin compatibility fixtures exist.

## Migration status (2026-10-12)

The registry, argument schemas, revision gates, and workspace edit boundary are
implemented. The TUInk built-in bridge still resolves many invocations back to
its command enum for execution in frontend routers. Registering an invocation
therefore does not yet mean that all built-in behavior executes inside the
document session. Consolidating receiver selection and execution results is a
separate migration; it must not move widget/view/file behavior into the document
engine merely to remove that bridge.

## Consequences

- The physical dependency graph prevents editor/plugin contracts from drifting
  into terminal packages.
- TUI-specific command IDs may remain in `tuink-tui`, but their value type and
  dispatch semantics no longer belong to that frontend.
- Adding another shared DTO requires an explicit contract decision, not waiting
  for a Gradle cycle to appear.
- The current legacy action bridge remains transitional; handlers move behind
  the session registry incrementally.
