# Project search

Project search reads the working folder, including files without an open tab.
Use **View → Project search** or a distinct **Ctrl+Shift+F**. The left sidebar
has independent **Files** and **Search** [tool palettes](tool-palettes.md); the document stays on the right.
**Ctrl+E** expands Files and **Ctrl+B** hides or restores the whole dock.
Both palettes can remain expanded. Collapsing retains folder expansion, the query, filters, results and scroll.
Esc returns focus to the document. Hidden or collapsed palettes continue collecting the
current bounded search. **Stop** cancels it. Changing or closing the working
folder cancels the old search and clears its results, retaining open documents.

If no folder is open, Search offers **Open folder...**. The Explorer menu's
**Find in this folder** searches the selected directory. Its scope appears
above Find; expand Filters and choose **Whole folder** to restore the root.
Opening a folder from this action selects the ordinary Files palette; select
Search to enter the query.

The search form keeps the query, mode/options and primary action above the
results. A narrow palette uses a **Strict / Wildcard / Regex** dropdown with
descriptions; a wide palette exposes three direct buttons. The selected mode
has a check mark. Aa and Word have check marks and explanations on focus/hover.
The mode picker supports Up/Down, Home/End and Enter/Space; Esc cancels without
changing the query or results. The first Esc closes an open picker; a subsequent
Esc returns to the editor.

Source and Preview use the same mode dropdown. Case, Word, All tabs, ignore and
hidden-file flags use shared check buttons. Encoding selectors in Filters and
Settings use the same dropdown behavior. Palette and dialog inputs have an inverse
gray background with dark text, including empty space. Inline Source/Preview
Find and Replace use the ordinary notification-row colors, following the shared
exception for notification containers. Closed dropdowns and
check buttons use ordinary button styling; focus supplies the color highlight.
Open dropdowns invert the colors of their own containing surface. Status rows
above the results and help below them use full-width semantic color bands. These are
[shared TUIframe control contracts](controls.md).

**Search** becomes **Stop** while a run is pending or active, and **Refresh**
for outdated results. The result heading distinguishes pending/running work,
no matches, invalid expressions, stopped or limited searches, incomplete
coverage and outdated results. The collapsed palette header reports these
states as well. Correcting a query clears the preceding error before the next
debounced run. Very short palettes keep a compact query/action/status view.

## Query and results

Enter one line of text, up to 4096 UTF-16 units. By default a search starts
after a 200 ms pause; Enter and **Search** start it immediately. **Aa** toggles
case sensitivity. **Word** independently restricts results to whole words.
Choose the global search mode explicitly from the selector or direct buttons.
Local Source/Preview Find uses the shared mode dropdown. Strict matches the entered text literally. Wildcard supports `*` and `?`
with leftmost greedy, non-overlapping matching. Regex uses
[Java Pattern syntax](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/util/regex/Pattern.html),
including classes, quantifiers, groups, alternatives, lookarounds and backreferences.

Every mode searches one logical line. Regex anchors also find empty lines and
zero-width positions, without splitting surrogate pairs. Regex queries are
limited to 4096 UTF-16 units; input accesses share the operation work budget
and cancellation, and excessive recursion is reported as a search limit.
Malformed expressions produce a visible error and preserve the document.
The local Replace field inserts literal text in every mode; it does not expand
capture-group references such as `$1`.

Files are grouped in the result list. Names are emphasized and parent paths
and counts use a secondary style. Match rows show the one-based line and a
source fragment, with all visible occurrences highlighted. Empty matches have
a visible position marker. UTF-16 ranges are mapped through complete terminal
graphemes; controls are escaped before display. Highlighting uses the already
returned ranges and does not rerun the query during painting. Selection and
matches remain distinguishable without color.

When results have focus, the window status shows the selected file's full path
and its one-based line/UTF-16 column, clipped to the available status width.
In the list, Up/Down selects rows,
Left/Right collapses or expands a file, and Enter opens the selected match.
A single click selects; double-click opens a match or toggles a file group.
Down from Find moves to the results. Tab and Shift+Tab traverse available
controls. Source and ordinary Markdown Preview continue using Ctrl+F for
their own local Find.

An open Source buffer takes precedence over its file on disk, including
unsaved edits and named files not yet created. A `*` after a result group
identifies a buffer source. Linked Source/Preview presentations produce one
source. Untitled documents and independent frozen Preview snapshots remain
available through the existing all-tabs Find; project search reads Markdown
source rather than rendered Preview text.

Results describe the versions actually searched. Editing or closing a captured
Source marks the results outdated (`!` on its group). Use Refresh to update.
Opening a match checks the current document revision or loaded byte fingerprint
and verifies its range with the common matcher. Changed content triggers a
fresh search instead of applying an old position. There is no filesystem
watcher or atomic snapshot of the whole folder.

## Filters

Expand **Filters** to change this window's search:

- **Include**: comma-separated path masks; placeholder `e.g. *.java, *.md`;
- **Exclude**: comma-separated exclusions; placeholder `e.g. build/**, *.log`;
- **Add gitignore**: include files excluded by nested `.gitignore` rules; initially unchecked.
- **Add hidden**: include dotfiles and dot directories; initially checked.
- **Enc**: Auto, UTF-8, UTF-16LE/BE with BOM, Windows-1251, CP866 or KOI8-R.

Empty fields display secondary-colored examples on the ordinary input background,
including while focused, without inserting them into the actual masks.
The collapsed Filters button counts active restrictions: nonempty include and
exclude masks, enabled ignore rules, hidden-file exclusion, explicit encoding
and a subfolder scope. Applying ignore rules (unchecked **Add gitignore**) therefore counts as one
initial filter. Masks have the same syntax in all three text-search modes.

Masks use positive Git-style path patterns; use Exclude for exclusions rather than
`!` negation. Negation remains available inside `.gitignore`.
An empty Include accepts all otherwise eligible
files. Explicit exclusions and ignore rules still apply to included paths;
check **Add gitignore** to include ignored files. An excluded parent directory cannot
be re-included by a child's `.gitignore`, including when it is selected as a
subfolder scope or contains the working folder. Hidden-file visibility is independent
of the Files palette. Thus `.env` is eligible when **Add hidden** is checked unless an
exclusion or ignore rule removes it. The `.git` directory is always excluded.
Symlink entries and special files are not traversed. Explicit working roots
may resolve through a directory symlink. Ancestor `.gitignore` rules come from
the resolved folder's repository; shortcuts retain their displayed paths and
do not inherit rules from an unrelated repository containing the shortcut.

Auto shares the editor's BOM/strict-UTF-8/Windows-1251 detection policy.
Validation covers the complete file, including invalid UTF-8 beyond the first
input buffer. Automatic detection can require two streaming passes. Their
byte fingerprints must agree before matches are published. Decoding and line
splitting retain the editor's LF, CRLF, CR and UTF-16 coordinate semantics.
UTF-32 and ambiguous encodings require conversion or an explicit supported
encoding. A decoded NUL character is treated as binary content; UTF-16's NUL
bytes alone do not reject a text file.

## Defaults and limits

**Settings → Project search...** edits persistent defaults. Current include/exclude,
Add gitignore, Add hidden and encoding controls retain their window values. Automatic
search and resource limits apply to subsequent runs in the current window.

| Property | Default | Accepted value |
|---|---|---|
| `search.automatic` | `true` | `true` or `false` |
| `search.respectIgnore` | `true` | `true` or `false` |
| `search.hidden` | `true` | `true` or `false` |
| `search.exclusions` | empty | One line, at most 4096 UTF-16 units |
| `search.encoding` | `detect` | `detect`, `utf_8`, `utf_16le`, `utf_16be`, `windows_1251`, `cp866`, `koi8_r` |
| `search.maximumFileMiB` | `64` | Canonical integer from 1 through 64 |
| `search.maximumMatches` | `10000` | Canonical integer from 1 through 100000 |

These are optional fields of the current settings schema. Their absence uses
the defaults above; invalid values or unknown properties use the existing
Fix/Delete/Later flow. Queries, result text and panel focus are session state.

Search also bounds retained match data to 8 MiB, each logical line to 1 Mi
UTF-16 units, traversal to 128 levels and one million entries, and each
`.gitignore` to 64 KiB. These limits apply independently of the selected result
count. A file that changes, fails decoding or exceeds a limit contributes no
partial match list. Skipped files and errors are counted explicitly; choose
**Skipped** to inspect up to 100 recorded reasons. Limited and stopped searches
are labelled as such, rather than presented as complete results.

At most one search worker runs per window, with a single current pending
request and a bounded result queue. Cancellation is cooperative, checked during
enumeration, reading and matching. A superseded result cannot enter a new
query's model. Blocking platform I/O can delay physical worker completion;
the UI can revoke publication immediately without creating more worker threads.

The palette menu's **Hide hints / Show hints** controls contextual help below
the results and saves the choice independently of Files help. Hiding help
reclaims its row; search progress, errors and result summaries stay visible.
The selected result uses an accent only while the results list has focus and
is inverse when focus moves to the query, a header or another surface.
Search/Stop/Refresh use colored text on the palette body and invert on focus.
