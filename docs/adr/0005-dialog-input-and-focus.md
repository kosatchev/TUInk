# 0005: Dialog input and focus

Status: accepted

Date: 2026-10-12

## Context

Search, Save As, confirmation prompts, help, and the file picker had separate
rules for keyboard ownership, background access, and rejected input. A visual
`Modal` frame did not define these rules. This allowed clipboard commands on
search buttons to reach the document and made keyboard behavior differ between
dialogs.

Search needs to permit editing the underlying document. Save As and
confirmation prompts permit inspecting and copying the document while an
operation is pending. Help and the file picker keep all interaction inside
their own controls. These require separate input and focus policies.

## Decision

TUIframe provides the shared behavior in `tuiframe-widgets`:

- `DialogMode` distinguishes `NON_BLOCKING`, `BLOCKING`, and `EXCLUSIVE`.
  Non-blocking dialogs allow content editing; blocking dialogs allow read-only
  access; exclusive dialogs reject all content input and exclude content from
  focus traversal. These modes do not stop the event loop.
- `DialogFocusTraversal` cycles through eligible dialog controls and a separate
  content stop in either direction when the mode permits content focus. Empty,
  disabled, clipped, and unavailable surfaces are skipped. It also focuses the
  first available dialog control.
- `DialogInputPolicy` evaluates application-classified actions against the
  current target and mode. Clipboard commands on buttons are ignored. Local
  command support belongs to the receiving control: a field with its own history
  may handle Undo/Redo; unsupported local commands are consumed without falling
  through to content. Blocking dialogs reject content modifications,
  cut, paste, undo, and redo while permitting navigation, selection, and copy.
  Exclusive dialogs reject every action directed at content, including
  navigation, selection, copy, and scrolling. Controls retain the same local
  behavior in all three modes.
- A blocked decision is consumed and invokes focus-first followed by attention.
  Ignored input has no rejection effects. Consumers must discard consumed input
  rather than replay it into the newly focused control.
- `DialogAttention` schedules a short, bounded highlight and its restoration.
  `DialogAttentionPainter` paints it without replacing text or splitting wide
  cells. The application supplies geometry and styles.

`Modal` and `PlacedModal` remain visual frame primitives. The behavior API also
works with inline prompts that have no frame.

`RoutedTarget` derives current-frame interaction availability from the authored
enabled flag, placement bounds, parent clip, and `InteractionVisibility` policy.
`FULL_BOUNDS` requires a nonempty control wholly inside its clip; scrollable
surfaces can explicitly use `VISIBLE_INTERSECTION`. `FocusManager` and
`DialogFocusTraversal` use the same derived eligibility. Buttons and text fields
also reject ordinary direct input when their placements are clipped. A scope
with the `CLEARED` reset policy clears an unavailable focus target instead of
automatically choosing another action after a resize.

`FocusManager.peekContext` reads a target's current focus and availability for
painting and cursor placement without entering or reconciling its scope. It
leaves retained focus and explicit clearing intact, including when a target has
become clipped or another scope is active. Input routing and `context` still
perform activation and reconciliation. Search and Save As use this shared read
operation instead of maintaining their own focus projections.

`PlacedInteractiveWidget.interactionReady()` also checks the allocation itself.
A button requires room for its complete rendered label even when its smaller
allocated bounds fit inside the clip. Transparent placement adapters forward
this capability; fields and scrolling surfaces require their viewport to fit,
not their whole text content.

`PlacedInteractiveWidget.mapOutput` adapts a control's typed actions without
changing its placement behavior. It forwards hit testing, readiness, painting,
focus and capture effects, and exposes cursor and pointer-capture capabilities
only when the original placement provides them. Search and Save As supply their
own action mappings through this shared adapter.

Form actions have additional application-owned context requirements: a readable
confirmation question, a filename viewport, or both search fields. Each placement
computes this condition once and exposes it to physical and semantic command
routes. Action availability does not require its button to be visible: Enter can
submit a readable filename while the Save button is hidden. Safe cancellation
remains available without visible controls.

TUInk classifies its commands in `DialogCommandPolicy` and supplies the actual
control scopes, document geometry, clipboard adapter, and rendering. File and
menu commands aimed at a document behind its blocking or exclusive dialog remain
unavailable. Tab navigation is routed before view-local blocking input so another
tab remains usable and the originating dialog retains its controls and focus.
Quit confirmation, startup recovery/settings, and Explorer file-action dialogs
keep application-wide ownership. Explorer operations address the window-owned
tree selection rather than the selected document presentation.
Escape and the existing per-dialog Ctrl+Q actions continue through the dialog's
cancellation policy.

Quit and close-tab confirmations map `Y` to Save and `N`, Escape, or Ctrl+Q
to Cancel. Discard requires explicitly activating its button. These choices
use the existing save continuations, including Save As and multi-tab saves.

Recovery offers Restore, Delete, and Later, with Later initially focused.
`Y` chooses Restore and `D` chooses Delete, case-insensitively. The recovery
shortcut policy supplies both input bindings and the single visible hint:
`Y=Restore  D=Delete  N/Esc=Later  Ctrl+Q=Quit`; the question contains only the draft
count. Recovery and encoding confirmations have separate shortcut policies.
`N` and Escape choose Later, even when the terminal is too small to
show the question. Postponing recovery keeps the startup snapshots on disk
through a later normal quit; the recovery store releases their claims immediately,
so another instance can offer them without waiting for this editor to close.
Only explicitly activating Delete, including `D`, removes the offered snapshots;
it does not change the original files. Ctrl+Q releases the offered snapshots
without deleting them and starts the ordinary application-close workflow,
including Save/Discard/Cancel for current unsaved documents. Cancelling that
exit keeps the current edits and the offered snapshots. A release failure leaves
the failed entries pending in the recovery prompt and prevents exit. This safe
Quit action is available even when the question is clipped; it is a keyboard
action and does not add a fourth button. Restore and Delete remain unavailable while
their question or controls are clipped.

Search reserves room for its fields before showing option and action groups.
Replacement requires both field placements to remain visible; the window checks
the same condition before physical or semantic replacement commands execute.

`TextField` owns its text, selection gesture data, and horizontal viewport.
Cancelling capture preserves the viewing position; Copy and other actions that
leave the text and caret unchanged must not scroll the field. A shared TUIframe
capture coordinator owns delivery of drag/release beyond the current hit box and
termination on surface/focus/geometry changes. Capture identity is stable across
frames; delivery resolves the current placement instead of retaining an obsolete
placement. The widget owns the gesture data; the coordinator owns delivery.
Capture routing can deliver a drag or release beyond the hit box while the owner
still intersects its clip. A widget can require stronger visibility: TextField
cancels if its own bounds become clipped, preserving its selection and viewport.
Continuation does not admit a new press or keyboard activation on an unavailable
control.
TUInk reports active-surface transitions and adapts its document gesture to this
contract. Document wrapping anchors and reading-mode command effects belong to
TUInk, which owns the text model and command semantics.

`PlacedPointerCapture` and `InteractionResult` capture effects are implemented
by TextField, ListView, TreeView, and MenuBar. `PointerCaptureManager` stores only
the stable surface/scope/target address. TUInk's `WindowPointerCapture` supplies
an ephemeral current-input frame containing typed bindings and result adopters;
it does not retain placements or callbacks between frames. One delivery path
serves search, Save As, help, menus, the file tree, and the document adapter.

`MenuBar` allows activation only when the complete item label fits in the
current popup. Enter, pointer, ordinal, and mnemonic routes share that check;
clipped labels use the disabled style. Navigation and Escape remain available,
and resizing recalculates whether an item can be activated.

Cancellation runs while the owner binding still exists and adopts its ordinary
widget output. An immutable placement is rebuilt after cancellation before
normal routing continues. Focus and capture are independent, so dragging a
scrollbar does not require keyboard focus. The shared `Explorer` owns file-row
gesture state, activation and the event's double-click clock; its capture adapter
routes continuation and cancellation through the current placement.

Semantic local commands use `ControlCommandRouter` and the same Search/Save As
raw-control routes as keyboard input. Unsupported local commands stay consumed
by TUInk's control adapter. `KeyStroke.SPACE` and `TextInput(" ")` have identical
local meaning in text fields, without application-specific event conversion.

`tuiframe-explorer` applies an immutable access profile per instance. TUInk's
sidebar uses `MANAGE_FILES`; Open, folder-selection and move/copy-destination popup
trees use `READ_ONLY`. Read-only trees permit navigation, selection and scrollbar
capture, but cannot start file dragging or mutate files through their operations
object. A writable tree uses the shared stationary 500 ms hold before movement.
Early motion cancels the move until release, even after returning to the starting
cell and waiting. It paints a drag label and destination highlight, expands hovered
folders after 700 ms and scrolls at the viewport edges. Only release over a valid
destination emits one move intent. Escape, root/surface changes and geometry
changes cancel the gesture; its own hover expansion and scrolling preserve it.

Undo/Redo routing respects the same receiver boundary: Ctrl+Z/Ctrl+Y use file
history only with sidebar focus. Fields retain their own local history and never
fall through into filesystem operations; explicit Explorer menu commands can
operate while Source or Preview has focus. The explorer owns its warning/error
footer. Successful history notices are temporary and configurable; errors remain
visible. The shared `NoticeBanner` accepts owner-defined action IDs and labels,
with Dismiss as its default action. It has no file or editor semantics; each
action has its own routed button, clipping, enabled state and optional focus.
TUInk uses Dismiss for persistent deleted-file notices. External-change warnings
have no buttons and use a wrapped plain banner with File → Update from disk
(F1 → U) and File → Save (Ctrl+S) instructions. Both warnings occupy the document
pane independently of sidebar and temporary history-notice preferences.

Showing a notice never takes document focus or changes the caret. Source Tab
keeps its editing behavior. Clean bound documents reload automatically by
default; disabling the preference makes updates manual. Local edits, stale
bindings and unsuccessful reads retain the buffer and warning. Successful reload
or explicit save clears the external warning; cancelling does not. Saving over
external changes uses the shared confirmation control with an explicit loss
warning and Cancel selected initially. The write is authorized for the exact
inspected version, and a later disk change refuses publication. Deleted-file
notices keep their separate Dismiss/successful-save lifetime.

Search is non-blocking and includes the editable document in its focus cycle.
Save As and confirmation prompts are blocking: their focus cycles include the
document, which remains selectable and scrollable, and Copy uses the document
selection or line. Normal editing and clipboard operations work in Save As's
own text field. Its controls are the filename field, Save, and Cancel, followed
by the document stop. Enter in the field and activation of Save share the save
flow, including validation and overwrite confirmation; Cancel uses the existing
Escape cancellation. Buttons that cannot fit beside an editable field are
hidden and excluded from traversal. Saving requires a fully visible filename
viewport with room for a wide glyph and its caret; all submit routes share
that condition. Cancellation remains available when the field is clipped.
Help and the file picker are exclusive: Tab traversal stays
inside the dialog, and document input is rejected. A document caret covered
by a dialog is hidden; a document completely covered by the dialog is skipped
during focus traversal even when its mode permits content focus.

### Popup surfaces and file-action behavior

Help, License, About, Open, document-format and Explorer file-action windows use
one application popup theme. Its base surface, border and title resolve through
`dialog`, `dialogBorder` and `dialogTitle` in `UiStyles`. Ordinary buttons and
tree/list rows receive that light surface with dark text from their containing
popup. Text fields override it with the shared dark `dialogField` surface and
light text; focused, disabled and selected text use `dialogFieldFocused`,
`dialogFieldDisabled` and `dialogFieldSelection`. The whole input allocation,
including empty padding, receives its field surface. Focus, text selection,
disabled controls, branch emphasis and validation errors use shared semantic
widget roles. In particular,
`MODAL_ERROR` is supplied by the application palette; widgets do not choose
application colors. Both the Help and Open theme adapters delegate to the same
popup resolver as file-action windows. The editor surface remains the parent
surface for the sidebar, independently of popup styling.

Each popup owns one retained control scope and one exact-frame placement. Name
dialogs initially focus the text field; a rename selects its current basename.
Destination selection initially focuses its tree. Destructive confirmations
initially focus Cancel. Tab and Shift+Tab traverse only eligible controls.
Enter retains the receiving control's meaning: it submits a readable name,
navigates an entered path, or activates a focused button. Name submission and
the primary button execute the same validated operation.

Escape, the frame close control and the file-action dialog's Ctrl+Q cancel
unpublished work through the same owner path. During a committed mutation,
controls show the busy state and the owner accepts its actual result before
closing; cancel does not claim to roll back an already published filesystem
change. An error remains inside the popup while preserving its draft. Resizing
retains input and control identity; clipped controls cannot activate, and safe
cancellation remains available in the compact layout.

File identities use one application presentation policy across the picker,
tabs, menus and confirmation questions. If native bytes or terminal sanitization
make a literal ambiguous, the display uses a marked, percent-encoded label.
Clipping respects encoded tokens. A destructive action on an encoded name is
available only when its complete basename is visible; otherwise the existing
resize prompt applies. Presentation labels never determine the persistence path.

## Consequences

### Container geometry and inherited surfaces

`SurfaceContext` carries immutable bounds, the ancestor clip and inherited
widget roles. Children are placed in parent-local coordinates; explicit child
roles override inherited roles without changing siblings. The application
composes workspace, explorer, document and footer containers from these contexts.
Search and inline Save As retain their own surface overrides; popup controls
inherit the popup theme, including its contrasting text-field roles.

`WindowChrome` prepares footer surfaces once for both Source and Preview.
Search, Save As, view confirmations, notifications and status are children of
the document container. They reserve document height independently of the
explorer. Quit confirmation has a window-wide surface and keeps its global
close workflow. Fields, buttons, painting, cursor and modal hit bounds consume
the same prepared geometry; renderers do not infer a second footer allocation.

Preview uses the shared search composition in read-only mode, with a bounded
query, matching options and next/previous actions. Replacement and document
editing controls are absent from its scope. Focus and input admission use the
same search router as Source.

Common focus, rejection, capture, local buffer, timing, and painting contracts
are tested without a TUInk dependency. Application tests cover its clipboard
adapter, command recipients, and dispatch against the editor model. PTY checks cover real keyboard, mouse,
resize, focus transitions, and color and monochrome rendering.

TUIframe does not define editor commands, execute document operations, own a
window stack, or choose an application's dialog mode.
