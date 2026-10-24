# ADR 0002: document/session state versus frontend view state

- Status: accepted
- Date: 2026-08-24

## Context

`EditorState` originally combined document-adjacent editing state with terminal
viewport fields, and `TabSession` bound one terminal layout/cache to that state.
This could not be the portable session contract promised to future frontends.
A terminal viewport, focus, and cell layout are neither document state nor
safely shareable between frontends.

## Decision

The model has two explicit lifetimes:

1. `EditorDocumentSession` owns text, file metadata, revision, history, dirty state,
   workspace effects, and serialized mutation on the session thread.
2. Each attached frontend view owns cursor/selection, viewport, focus,
   layout/cache, presentation preferences, and its navigation revision.

View-local commands identify their frontend view. Text mutations are still
serialized by the document session and increment the shared document revision.
Portable clients observe immutable editor/document snapshots and do not receive
mutable `TextModel`, `EditorState`, or `EditorEngine`. TUInk's internal composition
adapter is an explicit exception described below; it is not the portable API.

Terminal line layout and terminal-width caches therefore remain owned by the
TUI view. `TerminalEditorTextLayout` is the single TUInk adapter to TUIframe's
terminal text cache. A GUI or another TUI view may use a different layout policy over the
same document snapshot. Recovery distinguishes document/file data from the
target view's saved position instead of treating viewport geometry as portable
editor state. The existing format-5 checkpoint remains an application
compatibility format.

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

All cursor and selection changes pass through the logical target API of
`EditorEngine`, preserving edit grouping and navigation invalidation. The
engine does not offer viewport navigation entry points. `EditorViewNavigation`
owns preferred visual columns, and `EditorViewCoordinates` supplies the shared
mapping used by navigation, pointer input, render planning, and resize anchors.
New portable contracts must not expose viewport, terminal style, layout-cache
keys, or mutable core objects.

`EditorDocumentSession` owns one `EditorDocument`, publishes immutable
`DocumentSnapshot`s, and accepts revision-guarded `WorkspaceEdit`s only on its
owner thread. Its logical headless view and attached TUI views use the same text,
file state, history, and savepoint chain. Snapshot publication is demand driven:
an owner-thread `snapshot()` refreshes by text revision, while a background read
returns the last published volatile immutable snapshot without reading mutable
core state. Unchanged revisions reuse the previous snapshot. Ordinary typing
does not copy and revalidate the whole document after each key. Before starting
background work, the owner captures a current snapshot; workspace-edit
validation also refreshes on the owner thread before checking revision gates.

`CoreSessionBridge` is a public Java class solely to cross internal module
boundaries. Architecture tests exclude that exact class from the portable API
scan and restrict its production consumers to `session.document` internals and
`TuiDocumentSession`. Other public session types cannot expose either mutable
core objects or the bridge. `TuiDocumentSession` owns the terminal attachment and
its stable `TabSession` identity. Explicit publication and owner-thread snapshot
requests cross its document boundary; background readers are not promised an
unpublished UI revision.

`editor-session` has a non-exported implementation dependency on `core`.
JGit diff is an optional `LineDiff` implementation in `editor-diff-jgit`, explicitly
attached by the TUI composition adapter; headless views neither load it nor
schedule gutter work.
Local file APIs and Linux FFM access remain in `core`.

## Implementation and compatibility (2026-10-15)

The core's `EditorDocument` owns its `TextModel`, `EditorDocumentState`,
`EditorDocumentHistory`, and saved/open diff baselines. `EditorViewState` owns
one view's positions and navigation revision. `EditorState` is the compatibility
facade over these two components; it no longer retains a second copy of their
fields. Each attached engine has its own derived `EditorGutterDiffState` and
layout; attaching a view does not initialize another history or reset document
savepoint baselines.

History entries associate document changes with an `EditorViewMemento` carrying
the initiating view identity. Undo/Redo restores that memento only for that view;
other views retain their own positions and clamp invalid positions after text
changes. Edit coalescing also observes view identity.

Recovery format 5 is retained. A checkpoint contains document/file data and the
position of the view being recovered. Restoring through that view applies its
position, resets the shared history/savepoint baseline as before, and leaves
other attached views with independent positions subject to document clamping.
The checkpoint is not reused as the portable document snapshot contract.

`EditorWindowController` implements the real event-loop handler. Tests enter
through `prepareTurn`, frame composition, and `handlePoll` instead of reflecting
private routers. Terminal acquisition, application construction, and reusable
application services remain outside that event controller.

## Consequences

- Simultaneous frontends do not fight over scroll position, focus, or preferred
  cursor column.
- TUInk render preparation reads the shared model and one view's state on the
  owner thread through its layout adapter. It produces an immutable `RenderPlan`
  for painting; `FrameComposition` borrows mutable core references only for that
  synchronous preparation and is not the portable snapshot API.
- Existing recovery format remains an application storage contract while its
  fields are migrated; it is not reused as plugin/editor API.
- `TabSession`, `EditorState`, and the internal core bridge remain composition
  compatibility surfaces rather than stable plugin contracts.
