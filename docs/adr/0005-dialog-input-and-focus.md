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

TUInk classifies its commands in `DialogCommandPolicy` and supplies the actual
control scopes, document geometry, clipboard adapter, and rendering. File,
tab, and menu commands remain unavailable from a document behind a blocking
or exclusive operation. Escape and the existing per-dialog Ctrl+Q actions
continue through the dialog's cancellation policy.

`TextField` owns its text, selection gesture data, and horizontal viewport.
Cancelling capture preserves the viewing position; Copy and other actions that
leave the text and caret unchanged must not scroll the field. A shared TUIframe
capture coordinator owns delivery of drag/release beyond the current hit box and
termination on surface/focus/geometry changes. Capture identity is stable across
frames; delivery resolves the current placement instead of retaining an obsolete
placement. The widget owns the gesture data; the coordinator owns delivery.
TUInk reports active-surface transitions and adapts its document gesture to this
contract. Document wrapping anchors and reading-mode command effects belong to
TUInk, which owns the text model and command semantics.

These ownership rules are migration requirements. Existing per-dialog capture
adapters and command exceptions are removed as consumers move to the shared
contracts; they must not become a second persistent route. Until each consumer
has migrated, real PTY regressions protect the existing focus and cancellation
behavior. `KeyStroke.SPACE` and `TextInput(" ")` have identical local meaning in
text fields, without application-specific event conversion.

Search is non-blocking and includes the editable document in its focus cycle.
Save As and confirmation prompts are blocking: their focus cycles include the
document, which remains selectable and scrollable, and Copy uses the document
selection or line. Normal editing and clipboard operations work in Save As's
own text field. Its controls are the filename field, Save, and Cancel, followed
by the document stop. Enter in the field and activation of Save share the save
flow, including validation and overwrite confirmation; Cancel uses the existing
Escape cancellation. Buttons that cannot fit beside an editable field are
hidden and excluded from traversal. Help and the file picker are exclusive: Tab traversal stays
inside the dialog, and document input is rejected. A document caret covered
by a dialog is hidden; a document completely covered by the dialog is skipped
during focus traversal even when its mode permits content focus.

## Consequences

Common focus, rejection, clipboard, timing, and painting contracts are tested
without a TUInk dependency. Application tests cover command classification and
dispatch against the editor model. PTY checks cover real keyboard, mouse,
resize, focus transitions, and color and monochrome rendering.

TUIframe does not define editor commands, execute document operations, own a
window stack, or choose an application's dialog mode.
