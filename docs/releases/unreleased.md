# Unreleased

Changes on the development branch after [v0.16.0](0.16.0.md).

- Verify the pinned save destination against the loaded baseline so a repeatedly
  redirected opening symlink cannot make an ordinary save overwrite another file.
- Coordinate Unix save locks across bind-mounted aliases of the same directory
  entry while retaining the previous lock for existing canonical paths.
- Show progress and allow cancelling file verification and save preparation,
  including overwrites of large existing files. Retain exact-content approval
  checks. Bound publication waiting on quit/shutdown and preserve recovery
  permission for unconfirmed outcomes; abandon pending opens without waiting.
- Sample footer and confirmation access hints in the background instead of
  querying filesystem permissions during rendering.
- Describe file failures consistently across open, save and directory loading;
  recognize typed access failures without mistaking words in filenames for
  permission or disk-space errors.
- Bind Save As approval to the inspected target address and version; reject
  target changes before writing or publishing the staged contents.
- Prepare recovery ownership before publishing restored tabs and retain snapshot
  text when metadata publication has an uncertain result or cleanup fails.
- Share immutable text blocks in document snapshots and prepare mandatory session
  results before committing edits or history replay. Render and session consumers
  use the same document identity.
- Return semantic highlighting tokens independently of terminal rendering. Move
  JGit diff and Linux FFM bindings into optional platform modules, and compile
  the shared engine libraries against Java 17 with an external JVM/D8/R8 probe.

- Stop reading terminal input when the final background save completes a confirmed
  exit, keeping the saved document and recovery state consistent.
- Remove retired empty license directories during archive upgrades, and reject
  unexpected managed-file types before installation or removal changes any files.
- Bound file-picker scans across the complete expanded tree, including metadata
  counting, and report limits or worker failures without leaving a loading state.
- Prepare bulk edits and undo/redo text, history and shared-view positions before
  publishing their state; failed preparation retains the previous draft.
- Continue sequential search from accepted matches with a bounded result cache
  and sparse checkpoints, preserving non-overlapping matching rules.
- Read and write document contents in a background worker. Saves temporarily
  lock the affected document's edits while navigation and other tabs remain usable;
  completion retains file-conflict checks, format history and quit/close flows.
- Align editing and terminal grapheme boundary checks for offsets outside the
  document, and describe workspace edits using their current preparation model.
- Share unchanged blocks during local edits to long lines and update Unicode and
  terminal indices from their edited ranges. Read visible text by range and use
  virtual wrapped rows for fixed-width runs. General word/tab reflow remains linear.
- Capture recovery text as immutable blocks and assemble it in the background;
  attempt final recovery even if an outstanding document operation fails to drain.

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
