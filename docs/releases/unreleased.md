# Unreleased

Changes on the development branch after [v0.18.0](0.18.0.md).

- **Folder explorer:** Explorer → Open folder... or `tuink .` opens a directory tree
  beside Source and Markdown Preview. Folder selection belongs to the window;
  switching or closing the root preserves open documents and unsaved edits.
- **Keyboard and mouse:** Ctrl+B toggles the explorer, Ctrl+E focuses it, and
  Esc returns to the document. The divider is draggable; narrow terminals
  temporarily hide the panel. Explorer → Refresh explorer reloads external changes.
- **Reusable layout:** TUIframe gains generic split-pane geometry and a splitter
  using the existing pointer-capture contract. Right on an expanded tree branch
  selects its first child.
- **Explorer controls:** F7 opens the Explorer menu with file/folder creation,
  Rename, destination-tree Move, Move to Trash, permanent Delete with confirmation,
  and 24/30/40-column width presets. The root is selectable for creation and cannot
  be renamed, moved or deleted. Directories are bold and the sidebar uses the
  editor background.
- **Shared explorer:** `tuiframe-explorer` owns the file model, UI, mouse gestures,
  mutations and file history. TUInk enables file management in its sidebar;
  Open, folder selection and move-destination popup trees remain read-only.
- **Drag to move:** Hold an entry for 1 second and drag to a highlighted folder.
  A label follows the pointer, hovering expands nested folders, and edge dragging
  scrolls the tree. One move starts on release over a valid target; Escape cancels.
- **File Undo/Redo:** Explorer menu commands reverse moves, renames and creation;
  Ctrl+Z/Ctrl+Y use this history only while the sidebar has focus. Undoing creation
  requires the same empty entry and refuses to remove saved content or populated
  folders. Destination and identity checks preserve conflicting entries.
  Mixed creation, copy, rename and move chains retain their identities across
  repeated Undo/Redo, including transfers between filesystems. Published transfer
  identities keep external replacements protected; copied links retain timestamps.
  Successful Undo/Redo shows a temporary yellow sidebar notice, controlled by
  Settings → File Undo/Redo notifications (enabled by default); errors stay visible.
- **Copy files and folders:** Explorer → Copy opens a read-only destination picker
  and copies the disk contents without rebinding open documents or overwriting an
  existing entry. Undo refuses a changed copy; Redo refuses a changed source.
  Copies exceeding the bounded history limit complete with a warning and no Undo.
- **System Trash:** Move to Trash uses the Linux system Trash when available
  and never falls back to permanent deletion. Trash and permanent deletion are
  not undoable in TUInk and clear earlier file history; system tools can restore
  trashed entries.
- **File operation lifetime:** Moves retain open documents, dirty state and
  undo history while updating resource bindings, including Preview. Deleting
  an open file retains its text as an unnamed dirty draft, with a persistent yellow
  banner below the document or Preview. Only a successful file save or Dismiss
  clears the banner. External missing-file checks run in the background and on
  activation, refresh and save, without a watcher. Structural changes refresh
  affected folders without rescanning unrelated expanded directories.
- **External file changes:** Active files are checked in the background, with
  metadata checks about every second and bounded content checks roughly every
  five seconds even when metadata is unchanged. Activation and Refresh request a
  fresh check; no OS watcher is required. Changed bytes show a persistent yellow
  Keep/Update banner in the document pane. Keep acknowledges the exact disk
  version while retaining local text; a later Save still uses guarded checks.
  Update accepts text and format in one Undo step, preserving earlier history
  and historical gutter markers. Undo retains the accepted disk baseline.
  File → Update from disk remains available after Keep. Own saves do not cause
  false warnings; stale results and read/decoding failures preserve the buffer.
- **Frozen external drafts:** Source and Live Preview share external decisions;
  frozen Preview has session Undo/Redo for them. Unsaved frozen text appears dirty,
  participates in close/quit Save/Discard/Cancel and recovery, and can save to its
  bound file. Recovery uses the existing schema and retains current text/format/
  baseline, without Undo history or the original text for historical gutters.
- **Containers and popups:** Explorer follows File. File-action dialogs use the
  common light Help/Open popup theme. Child surface contexts share geometry,
  clipping and inherited widget roles. Document footers use the document pane;
  Quit confirmation remains full-width and the sidebar keeps its own height.
- **Preview search:** The shared Find toolbar sits below Preview, with Case,
  word, wildcard and Next/Prev controls, without replacement or document edits.
- **Terminal colors:** Reported default and ANSI foregrounds are encoded
  explicitly on deep-color terminals, keeping the same color for ordinary and
  bold text. The explorer divider uses the theme accent without bold-dependent
  focus coloring. Modal input fields have a dark surface with light text and
  shared focus, selection and disabled styles.
- **Explorer fixes:** Pending reads preserve newer focus, dialogs, and tab
  selection. Encoding confirmation retains whether the file came from the
  sidebar or the file chooser. Clicking Find/Replace transfers keyboard input
  to the field, and closing Preview cancels its pending folder inspection.
  Automatic sidebar hiding restores the Preview caret immediately; tabs and
  content share the same available width even in very short terminals.

The explorer supports one root at a time. Root restoration across launches
and an OS filesystem watcher are not included.
