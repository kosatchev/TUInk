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

`editor-contract` owns the presentation-independent values and ports:

- `CommandId`, `CommandInvocation`, `CommandDescriptor`, and argument schemas;
- `DocumentId`, `DocumentSnapshot`, `TextPosition`, `TextRange`, `TextEdit`, and
  `WorkspaceEdit`;
- `LanguageId` and `LanguageRegistry`, the JDK-only filename/extension identity
  shared by editing and highlighting. The registry owns aliases, including
  Python stubs/windowed scripts, Markdown aliases and C++ source/header suffixes.
  Indentation, comments, lexical scanning and colors remain consumer policies.
- `DocumentCommand`, its target and result, `DocumentBackend`, and the storage
  and recovery contracts used by the document owner and platform providers.

`core` implements `DocumentBackend` through `CoreDocumentBackend` and uses the
shared command, document, language, storage and recovery contracts. The session
accesses the mutable implementation through that port without exporting its
document or views. Highlighters use the same language registry without depending
on mutable core classes.

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
the complete result is composed in ascending range order using original-document
coordinates and published atomically.

Mutable document state is accessed through an `EditorDocumentSession` bound to
its creating thread. Background consumers receive only immutable snapshots. A
workspace edit submitted from another thread is rejected using the last
published snapshot without reading mutable core state. On the owner thread,
validation first publishes the current snapshot, then rejects edits for another
document, a stale revision or an invalid range before changing document content.
Accepted batches cross one narrow core replacement boundary and produce one
undo entry.

Both modules are internal/incubating until plugin compatibility fixtures exist.

## Current implementation

The registry, argument schemas, revision gates, and workspace edit boundary are
implemented. TUInk's `CommandRecipient` selects the document, search controls, or
search operation after overlay access policy. `ControlCommandRouter` translates
semantic local commands to the same control input route used by keyboard events;
`FieldClipboard` supplies the application clipboard. `SearchCommandHandler`
executes document search operations; file flows remain application intents.
`InputActionDispatcher.DispatchResult` reports
the resulting viewport policy, including rejected commands, instead of asking
the viewport to predict success before execution.

The built-in registry resolves invocations to TUInk's frontend command enum.
Document operations then pass through `TuiDocumentSession` as typed
`DocumentCommand` values to `EditorDocumentSession.execute()`. This gate checks
the owner thread, document and view identities, text/state/view revisions, and
whether an in-flight save permits the command before invoking the backend.
Registration alone does not execute a document command. Widget, frontend view,
and file workflow behavior stays in its owning layer.

## Consequences

- The physical dependency graph prevents editor/plugin contracts from drifting
  into terminal packages.
- TUI-specific command IDs may remain in `tuink-tui`, but their value type and
  dispatch semantics no longer belong to that frontend.
- Adding another shared DTO requires an explicit contract decision, not waiting
  for a Gradle cycle to appear.
- The registry-to-built-in bridge remains internal. Typed document execution
  passes through the session gate; frontend behavior retains frontend receivers
  and does not become document-engine behavior.
