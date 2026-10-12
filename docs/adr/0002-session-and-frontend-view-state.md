# ADR 0002: document/session state versus frontend view state

- Status: accepted
- Date: 2026-08-24

## Context

The current `EditorState` combines document-adjacent editing state with terminal
viewport fields, and `TabSession` binds one terminal layout/cache to that state.
That works for the current single TUI, but it cannot be the portable session
contract promised to future frontends. A terminal viewport, focus, and cell
layout are neither document state nor safely shareable between frontends.

## Decision

The target model has two explicit lifetimes:

1. `DocumentSession` owns text, file metadata, revision, history, dirty state,
   workspace effects, and serialized mutation on the session thread.
2. Each attached `FrontendViewSession` owns cursor/selection, viewport, focus,
   layout/cache, presentation preferences, and a presentation revision.

View-local commands identify their frontend view. Text mutations are still
serialized by the document session and increment the shared document revision.
No frontend receives mutable `TextModel`, `EditorState`, or `EditorEngine`; it
observes immutable editor/document snapshots.

Terminal line layout and terminal-width caches therefore remain owned by the
TUI view. A GUI or another TUI view may use a different layout policy over the
same document snapshot. Recovery serialization separates document/file fields
from an optional frontend-view section instead of treating viewport geometry as
portable editor state.

Within a TUInk view, one coordinate service owns text-to-screen and
screen-to-text mapping, wrap boundaries, EOF hit testing, and the textual anchor
used when terminal dimensions change. Mouse navigation, keyboard navigation,
render planning, and scrollbars consume that service. The same view owns the
preferred visual column and the rules that reset it. These are editor-window
semantics; TUIframe supplies Unicode measurement, line wrapping, geometry,
layout caching, and scrollbar mechanics without knowing document positions.

Document history owns text mutations. A view's cursor/selection snapshot can be
associated with an undo entry to preserve current Undo/Redo behavior, but this
does not give the shared document ownership of every attached view's cursor.

Until `EditorState` separates document and frontend state, all cursor
and selection changes must pass through `EditorEngine` so its hidden movement
state cannot become stale. New portable contracts must not expose viewport,
terminal style, layout-cache keys, or mutable core objects.

`EditorDocumentSession` constructs and owns a
headless core internally, publishes immutable `DocumentSnapshot`s, and accepts
revision-guarded `WorkspaceEdit`s only on its owner thread. Its internal
`EditorState` is a temporary core implementation detail; no cursor, viewport,
layout, model, or engine object escapes through the session API.

For now `editor-session` has an implementation dependency on the existing
`core`, so its runtime also inherits current core dependencies such as JGit. It
is not yet a lightweight standalone artifact. The dependency must remain
non-exported; extracting a smaller `editor-core` is part of the physical state
split.

## Migration constraints (2026-10-12)

The physical state split remains a target until all consumers are switched.
Moving fields into a new class without removing the previous mutable owner is
not completion. Migrate coordinates and view navigation before splitting
history/recovery, keep one document model and one history throughout, and
preserve reading of existing recovery snapshots. A second view must have an
independent viewport/cursor while observing the same document revision. The
frontend event controller must be testable through the actual event entry point
without reflective calls to private routers.

## Consequences

- Simultaneous frontends do not fight over scroll position, focus, or preferred
  cursor column.
- Render preparation consumes immutable snapshots plus one view state.
- Existing recovery format remains an application storage contract while its
  fields are migrated; it is not reused as plugin/editor API.
- `TabSession` and direct engine calls are acknowledged migration surfaces, not
  stable platform contracts.
