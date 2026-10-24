# Unreleased

Changes on the development branch after [v0.16.0](0.16.0.md).

- Verify the pinned save destination against the loaded baseline so a repeatedly
  redirected opening symlink cannot make an ordinary save overwrite another file.
- Coordinate Unix save locks across bind-mounted aliases of the same directory
  entry while retaining the previous lock for existing canonical paths.
- Continue sequential search from accepted matches with a bounded result cache
  and sparse checkpoints, preserving non-overlapping matching rules.

- Encode saved documents into the staged file through bounded buffers, retaining
  original encodings, BOMs and line endings while hashing the written bytes.
- Reject search queries that exceed the compilation memory budget with an
  editable query and an error message, including large wildcard patterns.
- Resolve YAML block-scalar indentation after compact sequence and mapping
  indicators so following keys and lists regain their normal typing assists.
- Preserve exact search selections when undoing or redoing another view's edit.
- Keep keyword and closing-delimiter dedent available for nearby blocks after a
  long unrelated prefix. Lexical certainty uses bounded, cached context; owner
  searches retain their line and character limits.
- Keep rendering, resizing and stop requests responsive while draining a large
  terminal paste, including one rejected by the paste-size limit.
- Prepare keyboard edits, typing assists, clipboard cuts and their undo records
  before publishing text. A rejected edit preserves the draft, selection,
  viewing position and history, and reports an error without closing the editor.
- Share unchanged line-ending metadata between edits instead of copying the
  complete separator list for every inserted or removed newline.
- Update PGO training to activate Replace all through its button and recognize
  the current replacement feedback. UI regressions wait for saved file contents.
- Retain Python 3.10 support for recovery regression checks by hashing snapshot
  files incrementally with SHA-256.
