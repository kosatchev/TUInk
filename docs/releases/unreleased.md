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
  Rename, destination-tree Move, permanent Delete with confirmation, and 24/30/40-column
  width presets. The root is selectable for creation and cannot be renamed, moved or
  deleted. Directories are bold and the sidebar uses the editor background.
- **File operation lifetime:** Moves retain open documents, dirty state and
  undo history while updating resource bindings, including Preview. Deleting
  an open file retains its text as an unnamed draft. Structural changes refresh
  affected folders without rescanning unrelated expanded directories.
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
and automatic filesystem watching are not included.
