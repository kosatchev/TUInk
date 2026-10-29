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

The model has three explicit ownership boundaries:

1. `EditorDocumentSession` owns text, file metadata, revision, history, dirty state,
   document effects, and serialized mutation on the session thread.
2. Each attached frontend view owns cursor/selection, viewport, focus,
   layout/cache, presentation preferences, and its navigation revision.
3. `EditorWorkspace<T>` owns ordered document/view membership and selection by
   stable `ViewId`. Its opaque attachments hold frontend presentation objects.

View-local commands identify their frontend view. Text mutations are still
serialized by the document session and increment the shared document revision.
Portable clients observe immutable editor/document snapshots and do not receive
mutable `TextModel`, `EditorState`, or `EditorEngine`. TUInk composition retains
its core implementation privately; the portable session API exposes only neutral
values and ports.

Terminal line layout and terminal-width caches therefore remain owned by the
TUI view. `TerminalEditorTextLayout` is the single TUInk adapter to TUIframe's
terminal text cache. A GUI or another TUI view may use a different layout policy over the
same document snapshot. Recovery distinguishes document/file data from the
target view's saved position instead of treating viewport geometry as portable
editor state. Recovery checkpoints remain an application storage contract;
the current writer uses format 8 and the supported readers are described in
[the compatibility policy](../compatibility.md).

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

Frontend cursor and selection changes pass through typed session commands and
the engine's logical target API, preserving edit grouping and navigation invalidation. The
engine does not offer viewport navigation entry points. `EditorViewNavigation`
owns preferred visual columns, and `EditorViewCoordinates` supplies the shared
mapping used by navigation, pointer input, render planning, and resize anchors.
New portable contracts must not expose viewport, terminal style, layout-cache
keys, or mutable core objects.

`EditorDocumentSession` owns one neutral `DocumentBackend`, publishes immutable
`DocumentSnapshot`s, and accepts revision-guarded `WorkspaceEdit`s only on its
owner thread. Its logical headless view and attached TUI views use the same text,
file state, history, and savepoint chain. Snapshot publication is demand driven:
an owner-thread `snapshot()` refreshes by text revision, while a background read
returns the last published volatile immutable snapshot without reading mutable
core state. Unchanged revisions reuse the previous snapshot. Ordinary typing
does not copy and revalidate the whole document after each key. Before starting
background work, the owner captures a current snapshot; workspace-edit
validation also refreshes on the owner thread before checking revision gates.

The session can attach a logical view by returning its stable ID; it never
returns a mutable core view. `CoreDocumentBackend`
implements the neutral host port and registers existing views by their stable
`ViewId`. TUI composition creates its terminal view, retains it for rendering
and geometry, and connects its backend to the session. Architecture checks forbid
frontend calls to engine text, selection, history and I/O mutators; all public
session types are checked for core API leakage without an exemption.

Built-in `DocumentCommand`s cover input, literal paste, clipboard, history,
logical navigation, pointer selection and search/replace. Execution targets a
document and registered view with expected text, semantic document state and view
revisions. Format-only save/history publications invalidate captured commands even
when the text stays unchanged; view moves invalidate deferred positional commands.
Each command uses its originating engine and the shared document history, so
coalescing and origin/foreign-view Undo retain their existing behavior. Commands
return bounded receipts prepared before publication, with no mandatory full
snapshot per key. Explicit snapshot publication remains separate.

`editor-session` has a non-exported implementation dependency on `core`.
JGit diff is an optional `LineDiff` implementation in `editor-diff-jgit`, explicitly
attached by the TUI composition adapter; headless views neither load it nor
schedule gutter work. Linux FFM access lives in `editor-storage-jvm` behind a
small service SPI. Neither provider enters the shared engine's runtime closure.
Resource/version, format, load/save and capability contracts live in
`editor-contract`. `core` only uses those contracts and a stream-based codec;
`editor-storage-jvm` owns paths and filesystem transactions. Public session I/O
captures on the owner, inspects and writes on a worker, then publishes a matching
receipt on the owner. Recovery restore prepares text, versions, diff baselines and
view clamps before publication. Portable I/O/recovery coordinators and workspace
membership are implemented; Android storage and platform lifecycle remain future work.

The session owns one canonical `DocumentId`, shared with live render/highlighter
adapters. Cache identity and resource binding are separate identities. Snapshot
lines share final immutable block values; UTF-16/line-separator summaries are
maintained during block edits. Explicit String access may materialize a line.
Required workspace-edit and history-replay snapshots/results are prepared before
text/history publication, so a preparation failure aborts the mutation.
Empty Undo/Redo results are also prepared before invalidating the view's navigation
revision. Allocation failure in that preparation or while starting a prepared open
is reported as an edit-limit failure without changing the document or view guards.
Publishing edits only invalidates derived gutter diff state; it does not call the
optional diff provider before publishing foreign-view clamps and recovery state.

## Implementation and compatibility

The core's `EditorDocument` owns its `TextModel`, `EditorDocumentState`,
`EditorDocumentHistory`, and saved/open diff baselines. `EditorViewState` owns
one view's positions and navigation revision. `EditorState` is the compatibility
facade over these two components; it no longer retains a second copy of their
fields. Each attached engine has its own derived `EditorGutterDiffState` and
layout; attaching a view does not initialize another history or reset document
savepoint baselines.

The workspace accepts a prepared batch only after validating the complete
membership and allocating its next immutable registry. One document session
cannot belong to two workspaces. The TUI `SessionContext` derives its active
model, state, engine and renderer from workspace selection; it keeps no second
mutable list of tabs or cached active-document pointers. Focus, dialogs and
geometry remain frontend state.

Each terminal workspace attachment retains a `TabUiState` for its `ViewId`.
It owns search controls and options, filename input, local dialogs, picker and
help state, notifications and focus managers. Manual tab selection preserves
these controls; the window prepares only the active view's placements and
cancels pointer capture when the view changes. Only quit confirmation and
startup recovery remain application-wide modals. Dialog and search badges are
projections of this retained UI state and never become document names or identity.

An explicit cross-tab search result copies query, replacement, options and focus
intent into independent destination controls while retaining the origin's search.
Ordinary tab switching does not copy or close search. Replace operations still
target the active document. After the user selects another tab, open/save
completion messages and prompts update the initiating view's UI without replacing
the selected tab's dialog or forcing activation. The
frontend file executor retains one operation in flight; view-local dialogs do
not change the document-level save lock shared by every view of that document.

Removing a view permanently retires its ID and rejects deferred commands for it.
Attaching a logical view does not admit it into a workspace. If admission is
abandoned or rejected, the host can retire the prepared view with
`EditorDocumentSession.detachView`; admitted views must be removed through their
workspace owner.
Other views retain the same document, history, save coordinator and independent
positions. A captured save retains its originating engine until receipt
publication even if that view detaches. The last workspace view cannot retire
while a save is pending. After the last view closes, a neutral logical engine
keeps document operations independent of the closed frontend layout; attaching
a new view replaces that temporary engine. `defaultViewId()` remains the
initial identity and may be retired: callers use the selected entry's `viewId()`
for commands and recovery after view removal.

History entries associate document changes with an `EditorViewMemento` carrying
the initiating view identity. Undo/Redo restores that memento only for that view;
other views retain their own positions and clamp invalid positions after text
changes. Edit coalescing also observes view identity.

Recovery retains its application format. A checkpoint contains document/file data and the
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
