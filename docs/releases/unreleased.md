# Unreleased

Changes on the development branch after [v0.18.0](0.18.0.md).

- **Folder explorer:** File → Open folder... or `tuink .` opens a directory tree
  beside Source and Markdown Preview. Folder selection belongs to the window;
  switching or closing the root preserves open documents and unsaved edits.
- **Keyboard and mouse:** Ctrl+B toggles the explorer, Ctrl+E focuses it, and
  Esc returns to the document. The divider is draggable; narrow terminals
  temporarily hide the panel. View → Refresh explorer reloads external changes.
- **Reusable layout:** TUIframe gains generic split-pane geometry and a splitter
  using the existing pointer-capture contract. Right on an expanded tree branch
  selects its first child.

The explorer supports one root at a time. Root restoration across launches,
automatic filesystem watching, and filesystem editing actions are not included.
