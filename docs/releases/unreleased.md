# Unreleased

Changes on the development branch after [v0.17.0](0.17.0.md).

- **View → MD Source** (`Ctrl+P`) opens or selects Source at the current
  Preview position. Empty or unfinished previews still allow returning to
  Source. The separate **Go to source** command and its `Ctrl+Enter` binding
  are removed.
- Returning from Preview preserves the source caret at the end of text, including
  emoji and combining characters. Reopening a closed Source retains the decoder
  of the last read or saved bytes, independently of unsaved format conversions.
- Completing a save in another tab no longer cancels a pending system-clipboard
  paste into an unchanged document or field.
- **MD copy as displayed** exports the same visible control-character and
  grapheme replacements as Preview, instead of retaining hidden controls.
- Settings load failures are visible inside the editor as persistent warnings.
  Explicit preference changes in Source or Preview explain when they apply only
  to the running session; incompatible profiles remain untouched.
- Markdown Preview highlights code blocks with the same language aliases and
  syntax palette as Source. Block-local multiline context and bounded background
  preparation preserve responsive scrolling, selection and semantic copying.
- Settings adds **MD code syntax** for language-specific highlighting inside
  Markdown Source code blocks, disabled by default. Disabled blocks keep their
  uniform orange style and prose keeps normal Markdown highlighting. The optional
  format 2 property keeps existing profiles readable.
- Mouse-invoked menu commands in live and snapshot Markdown Preview execute
  their intended action instead of selecting the document underneath. Preview
  search fields also preserve global menu commands without replaying the click.
