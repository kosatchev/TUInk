# TUInk

> **A familiar text editor that lives in your terminal.**

TUInk is a non-modal editor for people who want to stay in the terminal
without giving up familiar controls. Open a file and start typing: use ordinary
keyboard shortcuts, the mouse, menus, tabs, search and replace, syntax
highlighting, a tree-based file picker, and a folder explorer beside the document
without learning an editor command language.

## TUInk in action

### Tabs and Markdown preview

![README, Markdown Source, and Preview tabs with diagrams and formulas](docs/assets/tuink-tabs.gif)

### Folder explorer and menus

![TUInk with the project folder open, followed by each editor menu](docs/assets/tuink-menus.gif)

## Why TUInk?

- **Start editing immediately.** TUInk is non-modal and uses conventional
  shortcuts for opening, saving, searching, copying, undoing, and redoing.
- **Use keyboard and mouse together.** Click, drag, Shift+Click, menus,
  scrollbars, and keyboard navigation work side by side.
- **Work with real-world Unicode.** Cursor movement and selection respect
  extended grapheme clusters, including emoji, combining characters, and wide
  CJK text.
- **Find and navigate quickly.** Work in multiple tabs, browse a folder beside the document,
  search across every tab or the working folder, and replace matches in the active tab. Case,
  whole-word options and Strict/Wildcard/Regex modes help narrow the result.
- **Edit code comfortably.** TUInk provides syntax highlighting, line and word
  wrapping, auto-indent, autopairs, automatic lists, visible whitespace,
  rainbow brackets, and line-comment toggling.
- **See what changed.** Gutter markers distinguish edits since the last save
  from edits since the file was opened.
- **Read Markdown in paired tabs.** `Ctrl+P` opens or selects Preview immediately
  to the right of Source. Read framed tables, Mermaid flowcharts and sequences,
  and two-dimensional TeX formulas as terminal text.
- **Protect unfinished work.** Recovery snapshots and file-aware saves retain
  detected encoding, BOM, line endings, and supported filesystem metadata.
- **Choose how to run it.** Use Java 25, a native Linux archive, or a Debian
  package.

## Try it

Local JVM builds use Java 25 from any vendor and an interactive terminal.
The Gradle wrapper can run on a compatible JDK, such as 17, 21 or 25, and
automatically downloads a Java 25 toolchain when needed.
The packaged JVM ZIP runs on Java 25 or newer. See the
[setup and transfer guide in Russian](docs/setup-ru.md) for a new computer.
Interactive Linux runs also require `tty` and `stty` in `PATH`
(`coreutils` on Debian/Ubuntu).

Linux access ACLs are preserved directly through libc in both JVM and native
distributions; the `acl` package and a separate `libacl` library are not required.

```bash
./gradlew --console=plain :app:run
```

Open a file directly:

```bash
./gradlew --console=plain :app:run --args="README.md"
```

Open the current folder in the left explorer panel:

```bash
./gradlew --console=plain :app:run --args="."
# Installed application:
tuink .
```

An existing directory opens the explorer. A file opens in a document tab;
a nonexistent path keeps the usual new-file behavior.

For a filename containing spaces, quote it inside Gradle's argument string:
`./gradlew --console=plain :app:run --args='"my notes.txt"'`.

Use `--` before a filename that starts with a dash, for example `tuink -- --notes.md`.
Arguments after `--` are treated as filenames, including names matching CLI options.

Run `./gradlew buildHelp` for the complete local build command reference.

## Highlights

### Familiar editing

- multiple tabs with mouse and keyboard navigation;
- system clipboard integration, with a shared application clipboard as fallback;
- selection by keyboard, mouse drag, or Shift+Click;
- undo and redo;
- search across all tabs, with replacement in the active tab;
- menus, buttons, dialogs, text fields, trees, and scrollbars;
- configurable Cyan, Blue, and **Monochrome** themes.

**Monochrome** is a grayscale theme for both the editor and Markdown Preview. It keeps
syntax styles, bold text and underlining, with explicit neutral foregrounds
and a dark neutral document surface. Preview code blocks retain a full-width
dark-gray background. Bracket depths use gray tones; unmatched brackets use
an inverted neutral foreground/background. Syntax and Markdown Source code
highlighting remain configurable in this theme. Indexed/RGB terminals provide gray
gradations; ANSI-16 terminals use neutral fallbacks.
The theme uses `ui.theme=monochrome` in settings and `--theme=monochrome` on the CLI.

Window controls, Markdown Preview roles and syntax tokens have independent
immutable palettes. Source and code inside Preview use the same syntax palette.
Changing themes updates visual styles while retaining document text, lexical
analysis and Markdown layout. Menus, selections, search matches, notifications
and change markers also use neutral styles in the monochrome theme.

### Terminal-native behavior

- mouse input can be enabled or disabled;
- alternate-screen and monochrome modes are available;
- terminal state is restored after normal exit and handled failures;
- content is clipped safely when the terminal becomes very small;
- the native build works without a JVM on the target machine.

### Text and code

- grapheme-aware cursor movement, deletion, selection, wrapping, and hit
  testing;
- syntax highlighting for common programming, markup, data, and configuration
  formats;
- bracket matching, rainbow brackets, and visible whitespace;
- switchable line/word wrapping, auto-indent, autopairs, and automatic lists;
- two gutter-diff layers: `saved -> current` and `open -> current`.

Extremely long combining sequences are displayed as replacement glyphs while
the original text remains available for editing, copying, and saving.

Markdown list continuation and termination respect indented code blocks and
code fences, including fences nested in list items.
List markers accept spaces or tabs; continuation preserves their padding and
resets task checkboxes for `-`, `*`, `+`, and numbered lists.

For heavily changed documents, gutter markers can use a broader changed range
to keep editing responsive; unchanged lines inside that range may also be marked.

### Markdown preview

Use `Ctrl+P` or **View → MD Preview** to create or select the linked
Preview tab immediately to the right of Source. Both participate in normal tab
navigation; the selected tab fills the document area. `Ctrl+P` from Preview
or **View → MD Source** selects Source at the current Preview position. If
Source has been closed, it reopens the linked file from disk in a fresh Source
tab. An unsaved frozen Preview or one with external Undo/Redo history stays in
its own tab; Source opens independently so that retained text and history remain
available. An ordinary unchanged snapshot rejoins the Source/Preview pair.
Empty or unfinished previews open Source without moving its
cursor.

Preview is available for Markdown filenames (`.md`, `.markdown`, `.mdown`,
`.mkd` and `.mkdn`, case-insensitively). Save an unnamed draft with a Markdown
filename before opening Preview.

Two independent **Settings** options control future Markdown file opens:
**Open both Markdown tabs** creates the adjacent pair; **Open Markdown in Preview**
selects Preview. With only the second enabled, Preview opens on its own and
`Ctrl+P` reads the file into a fresh Source tab on the left. Both options default
to off and leave existing documents and recovery drafts intact.

Live Preview follows the current source buffer, including unsaved edits, with
its own position, selection and search. Tables and wide diagrams scroll
horizontally; math and Mermaid use Unicode cells without terminal image
protocols. `Ctrl+F` searches preview text, `Ctrl+A` selects all, and `Ctrl+C`
copies the selection or the whole preview. Edit also provides **Copy block
source** and **Copy table as TSV**. Esc leaves Find or cancels a dialog while
keeping Preview selected.

**Settings > MD copy as displayed** makes ordinary Preview copy use rendered
characters: formula layouts, diagram lines and arrows, table frames and quote
markers. It is off by default and remembered between launches. `Ctrl+C` and
optional right-click copy share this policy; explicit **Copy block source** and
**Copy table as TSV** keep their formats. Source tabs and text fields keep
their normal copy behavior.

Displayed copy includes selected content outside the visible viewport. It
preserves hard line breaks, code rows and the spaces needed to align multiline
formulas and diagrams. Automatic wrapping of ordinary paragraphs is joined
back into logical lines so pasted text can wrap to its destination's width.
Copy uses the accepted Markdown layout and contains plain text without terminal
escape sequences or interface chrome.
Control characters and unsupported grapheme clusters use the same visible
replacements as the screen.

Preview uses the same mouse scrollbars as Source, including both thumb drags
and track clicks. Its mode, position, search summary and `Ctrl+P` hint
appear in the bottom status row. Only an active Find input uses a separate row.

`Ctrl+W` closes the selected tab. Closing Source uses the usual Save / Discard /
Cancel decision; Preview may remain with its accepted immutable raw snapshot.
The closed editor and its earlier Undo history are released. Closing an unchanged
snapshot has no second dirty prompt. A later external Keep or Undo can leave
unsaved frozen text; that draft participates in close/quit confirmation and recovery.
Preview layout and its session history are not restored at startup. During publication the snapshot
stays open while TUInk waits for the result; an unconfirmed outcome keeps the app
and snapshot open for verification.

Save and Save As remain available in Preview. With Source open they save the
latest shared buffer; after Source closes they save the captured raw Markdown
and its encoding, BOM and line endings through the ordinary version and
publication checks. Confirmed Save As updates the linked filename and reopen
target without opening an editor. An unavailable resource reports a save error;
explicit Save As can export the retained snapshot. Reopening reads disk contents,
using the decoder of the last read or confirmed save. Unsaved output-format
changes do not change that decoder. Reopening does not restore the closed source
buffer or its history. Background results are applied only to their matching
presentation and generation.

Two English demonstration documents are included in `apps/tuink/samples/`:

- `demo_markdown_preview.md` covers formulas, diagrams, code highlighting and quotes.
- `demo_mermaid_complex.md` covers 18 supported diagrams and two expected diagnostics.

Open either document and press `Ctrl+P`. For example, from the repository root:

```bash
./gradlew --console=plain :app:run --args="apps/tuink/samples/demo_markdown_preview.md"
```

JVM and native archives include the same documents under `samples/`; Debian
packages install them under `/usr/share/doc/tuink/examples/`.

See the [Markdown preview guide (Russian)](docs/markdown-preview.md) for controls,
supported Mermaid/TeX subsets, snapshot behavior and resource limits.

### Project search

Use **View → Project search** or a distinct `Ctrl+Shift+F` to search the working
folder, including files without open tabs. The sidebar's **Files / Search**
[tool palettes](docs/tool-palettes.md) can stay open together and retain independent state.
`Ctrl+E` expands Files; `Ctrl+B` hides or restores the whole dock. In Explorer, **Find in this folder** limits the
search to the selected directory.

Search reads unsaved Source buffers in preference to their files on disk.
Results arrive in file groups with emphasized names and highlighted source fragments. Enter or
double-click opens a match with a checked selection. Editing a captured buffer
marks results outdated; Refresh updates them. Collapsing palettes or hiding the sidebar
keeps the search running; Stop cancels it.

Filters provide include/exclude masks, nested `.gitignore`, hidden files and
encoding. **Settings → Project search...** edits defaults and limits. Search
uses the same Strict/Wildcard/Regex matcher and independent whole-word option as local Find; skipped files and
limited results are explicit. See the [project search guide](docs/project-search.md).

The global mode selector shows explanations and becomes three direct buttons in
a wide palette. **Include files / Exclude files** display mask examples and the
Filters header counts active restrictions. One action changes between
**Search / Stop / Refresh**. Error, pending, empty and outdated states have
separate messages; selecting a result shows its full path and exact position in
the window status. Match highlighting and zero-width markers also work without color.

### File safety

Opening a file is limited to 64 MiB and may have a lower limit when available
heap memory is small or the file contains many lines. An oversized file reports
an error without changing open documents. Reads remain bounded if the file grows.

- Save As validation and overwrite confirmation;
- explicit handling of read-only files and dirty tabs;
- recovery snapshots for unfinished edits;
- preservation of detected charset, BOM, line-separator layout, and final-EOL
  state;
- explicit document encoding and line-ending conversion with Undo;
- preservation of existing POSIX permissions, owner/group, user-defined extended
  attributes, Linux POSIX access ACLs, and ACLs exposed by the filesystem provider;
- safe saving through symbolic links without replacing the link itself.

If an edit leaves an empty line between bare CR and LF endings, that empty
line's ending becomes CRLF so saving and reopening retain both line breaks.

Normal saves compare the destination with the bytes last loaded or saved.
An external conflict retains your edits and requires explicit confirmation
before replacing the inspected disk version. Use File → Update from disk to
reload instead, or Save As with a different name to keep both versions. These
checks also apply to recovered documents. Locks coordinate
TUInk instances of the same user across configuration profiles. Other editors
do not use these locks: a change after the final check can still be overwritten.
Save As checks existing destinations with streaming reads. Longer checks and
save preparation show their progress and can be cancelled with Esc, retaining
the original file and your draft.
Before assigning a new filename, Save As checks open documents. A target belonging
to another tab must be saved from that tab or replaced with a different name. This
includes physical file aliases and filenames reserved by unsaved named documents.
Generated copy and numbered names fit a 255-byte filename budget, shortening the
base name before checking availability. Names entered manually remain exact.

If an edit cannot be encoded in the original charset, saving offers **Save as
UTF-8** with Cancel selected initially. The explicit conversion writes UTF-8
without a BOM and preserves existing line endings. In an open Source it becomes
a separate undo step after a successful save; earlier edits remain in that history.

Use **File → Format…** to choose UTF-8, UTF-8 with BOM, UTF-16LE or UTF-16BE
with BOM, Windows-1251, CP866 or KOI8-R. Encoding and line endings can be changed
independently. **Keep** retains each existing line ending, including a mixed
layout; **LF**, **CRLF** and **CR** convert every line ending and set the ending
used by Enter. Conversion preserves the presence or absence of a final newline.
The footer includes BOM information and shows **Mixed** for differing endings.
Click ENC or EOL to open the panel at that field. Tab moves between the lists
and buttons; the arrow keys choose a value.

Applying a format changes the document in memory and creates one Undo step.
Use Save to write the result. Cancel leaves the document unchanged, and an
unrepresentable character produces an error instead of replacement bytes.
Converting the output encoding does not repair text decoded with the wrong
encoding. Use **Reopen with encoding…** to read the original bytes again;
discarding unsaved edits requires confirmation. A failed reopen retains the
current document.

Automatic detection uses BOMs, strict UTF-8 and the existing Windows-1251
fallback. Use **File → Open with encoding…** or **Reopen with encoding…** for
CP866 or KOI8-R, or pass `--open-as=cp866` / `--open-as=koi8-r`.
Explicit UTF-8 also recognizes
its optional BOM. Explicit UTF-16LE and UTF-16BE require the corresponding BOM.
UTF-16LE files starting with NUL can share the exact bytes of UTF-32LE files;
`--open-as=utf-16le` (also `--open-as-utf16le`) resolves this ambiguity. UTF-32
editing is not supported.

Existing-file saves copy supported filesystem attributes to the staged file
before replacing the original. If those attributes cannot be preserved, saving
fails and the original is retained. Atomic replacement creates a new inode;
other hard links keep their previous contents.

Where the filesystem exposes Unix mode bits, saves also preserve `setuid`,
`setgid`, and `sticky`, including after content edits. The staged mode is
verified before replacement; a filesystem that clears a required bit causes
the save to fail while retaining the original.

Linux access ACLs are copied with their named entries and exact mask. If the
original has no extended access ACL, any ACL inherited by the staged file is
removed. The ACL and mode are verified before replacement; a failure reports
an error and retains the original file.
Saving and recovery retain the original bytes of Unix filenames, including
names which cannot be represented as UTF-8 text.
To open those names, use the file picker or pass an ASCII percent-encoded URI,
such as `tuink --file-uri='file:///path/legacy-%FF.txt'`. Ordinary command-line
paths containing `�` are rejected because the JVM may have already replaced
undecodable filename bytes with that character. This also applies to a literal
`�` in a filename; the file picker and encoded URI keep its identity exact.
The picker, tab titles and file confirmations use an escaped `file:` label when
native filename bytes or nonprinting characters cannot be displayed literally.
For example, `file:a%FF.txt` and `file:a%FE.txt` identify different native names;
an ordinary filename `a%FF.txt` remains literal. These short labels are for display;
Save As uses the complete absolute file URI. Encoded names must fit in an overwrite
confirmation before its action is available; enlarge the window when requested.

Syntax highlighting uses bounded scanning and output budgets. Long lines or
unavailable lexical context may be displayed as plain text so that highlighting
does not prevent opening and navigating the document.
Lexical nesting, continuation state and returned ranges also have fixed limits.
Exceeding a limit leaves the unknown region plain and suppresses uncertain bracket
errors rather than treating an incomplete scan as a complete document.
Resumable lexical context is completed in subsequent frames, including while
the editor is idle, when another bounded scan can retain useful progress.

Local edits share unchanged text blocks and Unicode indices instead of copying
an entire long line. Visible text is read by range. Initial indexing still reads
the line; word wrapping with tabs and some Unicode boundary changes can require
a long rescan. These cases do not have a constant-time responsiveness guarantee.

## Controls

| Input | Action |
|---|---|
| `Ctrl+O` | Open the tree-based file picker |
| `Ctrl+B` | Show / hide the palette dock |
| `Ctrl+E` | Show and focus the folder explorer |
| `Ctrl+Shift+F` | Show project search and focus Find, when the terminal sends a distinct sequence |
| `Ctrl+S` | Save the current file |
| `Ctrl+K` / `Ctrl+Shift+S` / `Ctrl+Shift+F4` | Save As |
| `Ctrl+X` / `Ctrl+C` / `Ctrl+V` | Cut / copy / paste using the system clipboard when available |
| `Ctrl+A` | Select all text in the focused document or text field |
| `Ctrl+Z` / `Ctrl+Y` | Undo / redo in the focused document, field, or sidebar explorer |
| `Ctrl+N` | Open a new tab |
| `Ctrl+Tab` / `Super+Tab` / `Ctrl+PageDown` | Select the next tab |
| `Ctrl+Shift+Tab` / `Ctrl+PageUp` | Select the previous tab |
| `Ctrl+W` | Close the current tab |
| `Ctrl+F` | Open search and replace |
| `Ctrl+P` | Create/select paired Preview; from Preview select or reopen Source at the preview position |
| `Ctrl+/` | Toggle line comments |
| `Ctrl+Q` | Quit, with confirmation for unsaved work |
| `F1` / `Alt+I` | Open the File menu |
| `F2` / `Alt+E` | Open the Edit menu |
| `F3` / `Alt+V` | Open the View menu |
| `F4` / `Alt+T` | Open the Tab menu |
| `F5` / `Alt+S` | Open the Settings menu |
| `F6` / `Alt+H` | Open the Help menu |
| `F7` | Open the Explorer menu |
| Arrows / Page Up / Page Down / Home / End | Navigate the document |
| `Shift` + Arrow keys | Extend the selection |
| `Shift+Home` / `Shift+End` | Select to the line start / end, respecting Smart Home/End |
| `Ctrl+Home` / `Ctrl+End` | Move to the document start / end |
| `Ctrl+Shift+Home` / `Ctrl+Shift+End` | Select to the document start / end |
| `Ctrl+Left` / `Alt+B` | Move to the previous word |
| `Ctrl+Right` / `Alt+F` | Move to the next word |
| `Enter` / `Tab` | Insert a newline / tab |
| `Backspace` / `Delete` | Delete backward / forward |

Modified shortcuts work when the terminal sends distinct key sequences and the
desktop passes them through. For example, some terminals cannot distinguish
`Ctrl+Shift+S` from `Ctrl+S`, or `Ctrl+Tab` from `Tab`. Use another listed
shortcut when a combination is unavailable.

**View** groups document modes (including Reading mode), wrapping, appearance
and Markdown navigation. **Settings** groups close confirmation, mouse behavior,
editing preferences and Markdown preferences; **Tab width** sits beside Tab insert mode.

A standalone `Esc` can take up to half a second to act while TUInk waits for a
possible terminal key sequence. Unambiguous key sequences, including Alt
shortcuts, are handled immediately.

Mouse controls include click-to-position, drag selection, Shift+Click
selection from the previous cursor position, wheel scrolling, tab and menu
activation, scrollbar interaction, and file-picker navigation.
Holding a dragged pointer at an editor edge continues scrolling the selection
until the button is released or the gesture is cancelled.

Saving and copying preserve a manually scrolled document viewport. Each tab
remembers its own viewing position; navigation and editing resume following
the text cursor. Resizing wrapped text preserves the text position at the top
of the viewport. Editing commands rejected in reading mode leave the viewing
position unchanged. Keyboard input ends an active mouse drag before handling
the key.

Each tab also retains its search text, replacement, options, dialogs, and control
focus. Tab shortcuts and tab-bar clicks remain available while search, Save As,
the file picker, help, or a document confirmation is open. Return to the tab to
resume its controls. Quit confirmation and startup recovery apply to the whole
application.
Tab titles and the Tab menu show `?` for a pending dialog, `/` for open search,
`#` for search matches, and `*` for unsaved changes, including on inactive tabs.

With line wrapping enabled, Up/Down and Page Up/Page Down navigate displayed
rows; Shift+Up/Down extend the selection by displayed rows. A fully filled
final wrap segment has an extra empty row for the cursor after the text.

Without a selection, `Ctrl+C` copies the current line and `Ctrl+X` cuts it.
On an empty final line both operations do nothing and preserve the clipboard;
empty lines with a line ending are still copied or cut.

Copy and cut publish successful operations to the system clipboard; paste reads
its current text. The same integration serves the editor, Markdown Preview,
search fields, Save As, and the file picker's location field. Preview content
remains read only. A failed copy or an operation without anything to copy
preserves the previous clipboard. Terminal bracketed paste inserts the received
text directly.

On a local Linux desktop, install either `wl-clipboard` for Wayland or `xclip`
or `xsel` for X11. TUInk detects available helpers automatically; on macOS it
uses `pbcopy` and `pbpaste`. Helpers run outside the UI thread, with bounded
execution time and a 16 MiB UTF-8 transfer limit. Input typed after Ctrl+V keeps
its order while the clipboard is being read. Clipboard text is sent through
standard input, never shell commands or command-line arguments.

Without a supported helper or desktop session, TUInk retains its shared internal
clipboard. A failed system transfer reports a status warning and uses that
internal clipboard. SSH sessions use the internal clipboard; use the terminal's
own copy and paste shortcuts to exchange text with the local desktop.

**Settings > Right-click: copy/paste** enables an optional mouse shortcut;
it is off by default and remembered between launches. In a text area or field,
an unmodified right press copies the selection and collapses it at its current
caret. Without a selection it pastes at that caret, without moving it to the
pointer. Markdown Preview content allows only copying; its Find field remains
editable. The gesture uses the same clipboard and commands as `Ctrl+C/V`.
Buttons, menus, gutters and scrollbars retain their normal input behavior.
Mouse mode must be enabled and supported by the terminal.

Close a tab with `Ctrl+W` or Tab > Close Tab. Menu hints reflect the active key bindings.

With the search panel focused, use `Ctrl+F` for the next match, `Ctrl+G` for the previous
match and `Ctrl+R` to replace. Activate the `Replace all` button to replace all matches
in the active tab.
The `All tabs` option extends matching and next/previous navigation to all open tabs.
`Replace` and `Replace all` change the active tab.
A result in another tab carries a copy of the current query, replacement, and
options into that tab. The original tab keeps its search; later edits to either
tab's controls are independent. Ordinary tab switching leaves each search unchanged.
`Enter` advances to the next match from a text field. Enter or Space activates
the focused button, including option toggles. `Tab` and
`Shift+Tab` cycle through fields, options, buttons, and the document; `Esc`
closes search. Clicking a search control or the document also moves focus.
With the document focused, ordinary editing and `Ctrl+Z` / `Ctrl+Y` work
while search stays open; `Tab` continues to move focus. Undo and redo are
ignored throughout the search panel. `Ctrl+F` returns focus to Find.
After `Replace all`, close search with `Esc` before using `Ctrl+Z` to undo it.
Single and bulk replacements check the proposed result against available memory before
changing text or history. If the result is too large, the editor reports an error;
reduce the number of matches or shorten the replacement and try again.
Pasting uses the same admission policy and preserves the selection, text, and
undo history when there is insufficient memory.
Keyboard edits, including typing assists and cutting, also prepare their text,
line-ending metadata and undo records before publishing a change. A rejected
edit reports an error and preserves the draft, clipboard and viewing position.

Large terminal pastes are read in bounded portions so that rendering, resizing
and stop requests can still be handled. The complete paste is inserted as one
undo step; oversized pastes are drained and rejected without inserting a prefix.

With Find or Replace focused, Left/Right and Home/End move within the field;
`Ctrl+Left/Right` or `Alt+B/F` move by word. Hold Shift to select text, or use
`Ctrl+A` to select the entire field. `Ctrl+C` copies the selection and `Ctrl+X`
cuts it; without a selection both leave the field and clipboard unchanged.
Dragging the mouse selects field text; Shift+Click extends the selection.
Typing and `Ctrl+V` replace selected text, joining pasted lines with spaces.
Pasting into Find, Replace, or Save As preserves tabs.
Field edits prepare their value and layout before applying the change. Input
that exceeds available memory is refused with a message, preserving the field.
For a Unix filename that cannot be represented as UTF-8, Save As shows an ASCII
`file:` URI with percent escapes. Leaving that value unchanged retains the
original path; ordinary filenames continue to use normal path text.
`Ctrl+Backspace/Delete` delete by word. Up/Down stay in the single-line field;
use `Tab` / `Shift+Tab` or a click to move focus. Page Up/Page Down and the mouse
wheel scroll the document while preserving its caret, selection, and field focus.
These field shortcuts leave the document text unchanged.
`Ctrl+C` / `Ctrl+X` / `Ctrl+V` are ignored when a button or search option is focused.
In narrow terminals, search controls wrap onto additional rows to keep options and actions accessible.
Replacement commands are unavailable while either field is hidden or clipped.

Save As includes the document in its `Tab` / `Shift+Tab` focus cycle.
Confirmations cycle their action buttons; click the document to inspect it.
`Ctrl+S` saves in save confirmations, `Esc` cancels, and Enter activates the
focused action (initially Cancel). Narrow confirmations wrap the full question
and actions rather than hiding file identity. While either prompt is open, the document allows navigation,
selection, copying, and scrolling. Attempts to edit, cut, paste, undo, or redo
the document briefly highlight the dialog and focus its first available
control; the rejected input is discarded. Save As keeps its field editable
and uses the same field clipboard behavior as Find and Replace.
Its focus order is filename, Save, Cancel, then document. `Enter` in the filename
field saves; `Enter` or Space activates a focused button. Cancel closes the prompt
without saving, just like `Esc`. Buttons are hidden in narrow terminals when there
is not enough room to retain an editable filename field. Saving requires room for
a filename glyph and its caret; enlarge the terminal when prompted. `Esc` still cancels.
A failed save keeps the prompt open and returns focus to the filename. Its error
stays visible until the filename changes or the prompt closes. Submitting an empty
filename shows an error and preserves any pending quit or close-tab operation.

Help and the file picker keep focus inside the dialog and block input to that
tab's document, including selection, copying, and scrolling. `Tab` / `Shift+Tab` cycle
only through their controls. Input aimed at the document briefly highlights
the dialog, focuses its first available control, and is discarded.

In the file picker, `Alt+.` or `Ctrl+H` toggles hidden files; the Hidden button
is also reachable with Tab. `Ctrl+L` focuses a path field with normal text editing
and clipboard shortcuts. Enter opens the file or navigates to the directory.
Relative paths are resolved from the displayed directory. With the tree focused,
`Backspace` moves its root to the parent directory, Left/Right collapse or
expand folders, and `Shift+Left` / `Shift+Right` scroll long names horizontally.
`Enter` or a double-click on an entry's name opens a file or makes a directory
the root. A single click selects an entry; clicking a directory's arrow only
expands or collapses it. `Tab`
and `Shift+Tab` move between the tree, OK, and Cancel; `Esc` closes the picker.
Enter or Space activates the focused button, including Hidden.
In terminals where Backspace sends `^H`, use `Alt+.` or the Hidden button.
Legacy `Ctrl+H` is interpreted as
Backspace because the two keys send the same byte.

The picker is read-only: browsing never creates, renames, moves or deletes entries.
The picker bounds the complete expanded tree to 20,000 entries and a memory
budget. A directory that exceeds the remaining budget is omitted with a visible
error; its contents are not presented as a complete listing. Directory item
counts may end in `+` when their counting budget is reached.

**Explorer → Open folder...** opens a directory chooser. Navigate into the desired
directory or select it in the tree, then activate **Open folder**. Selection
updates the Path field; a manually entered path can also be confirmed directly.
The chosen directory becomes the explorer root. Enter on a directory navigates
inside the chooser. You can also
start with `tuink .` or `tuink path/to/folder`.

The **Files** tool palette stays on the left while Source or Markdown Preview occupies the
right. It shares a resizable dock with Search. It belongs to the window and keeps its selected entry, expanded folders
and scroll position while you switch tabs. Opening another root or choosing
**Explorer → Close folder** leaves open documents and unsaved edits intact.
**Ctrl+B** toggles the palette dock; **Ctrl+E** expands Files and moves focus into it.
Use the header arrow to collapse, drag its title to reorder, or open **⋮** for
Maximize/Restore and keyboard-accessible size and move operations. The preferred
layout is saved on exit; see [Tool palettes](docs/tool-palettes.md).
An empty panel offers an **Open folder...** action. With the tree focused:

- Up/Down, Page Up/Page Down and Home/End move the selection.
- Right expands a folder or enters its first child; Left collapses it or selects its parent.
- Enter toggles a folder or opens a file and returns focus to the document.
- A single click selects an entry; double-click opens a file or toggles a folder.
- **Esc** returns focus to the document. Tab in the document keeps inserting indentation.
- **Alt+.** or a distinct **Ctrl+H** toggles hidden files; **Shift+Left/Right** scrolls long names.

File trees inherit their containing surface. The sidebar uses the editor's
background; unselected directory names are bold, including the selectable root
row. Selected directories use the same row style as search groups. Floating
file-action windows share the light Help/Open popup surface and button styles.
Their input fields use a dark background with light text, with distinct focus,
selection and disabled states. The divider uses the active theme's border color
without adding bold when the explorer has focus. Selection, disclosure marks
and scrollbars inherit their container.

On 256-color and true-color terminals, TUIframe requests the default foreground
and ANSI palette through read-only terminal color queries. Reported foregrounds
are rendered explicitly so bold retains the same text color; 256-color output
uses the nearest extended-palette shade. Responses are decoded separately from
keyboard input and pasted text, without delaying startup or changing the terminal
palette. Terminals without color-query support retain their native color and bold
behavior, as do ANSI-16 and `--no-color` output.

The Explorer menu creates files and folders inside the selected folder, or
beside a selected file. **Rename...** selects the current name for replacement
without overwriting another entry. **Move...** and **Copy...** open a separate
destination folder tree; Ctrl+L edits its path, Enter navigates, and **Move here**
or **Copy here** confirms the destination. The tree initially uses the open folder
as its root and displays relative paths, like the sidebar. **Use full path** is
off by default; enable it to browse outside the open folder. Entering an outside
path enables it automatically. Switching the view preserves the chosen destination.
Copy retains the source and copies
the on-disk file or directory; unsaved editor changes remain in their open document.
Existing names are never overwritten. **Move to Trash...** uses the Linux system
Trash when available; failure never falls back to permanent deletion.
**Delete permanently...** separately removes the selected entry and any directory
contents after confirmation. The open root cannot be renamed, moved or deleted;
it can be copied to a folder outside its own tree.
Open document text, unsaved edits and
text undo history survive renames and moves.

**View → Document Path** shows a path below the document tabs (or below the menu
when the tabs are hidden). It is enabled by default and saved in preferences.
Files opened from the explorer show paths relative to the open folder; files
opened from the command line or the file picker show full paths. Hiding the
sidebar keeps relative paths; closing the folder, changing to an unrelated root,
or saving outside it shows the full path. Source and Preview share this policy.
Long paths collapse middle directories to `…` before shortening the filename,
preserving its extension where space permits. Deleted drafts retain their original
path with a **(Draft)** suffix; unnamed documents show **Untitled**. Very short
windows temporarily hide the path row to leave room for document content.

To move with the mouse, hold an entry without moving for 500 ms and drag it onto a folder.
A label follows the pointer and the destination is highlighted. Hover over a
collapsed folder to expand it; the tree scrolls near its upper and lower edges.
Only releasing over a valid destination starts one move. Escape cancels the
gesture. The file pickers in popups allow browsing without file operations or
file dragging.

**Explorer → Undo / Redo** reverses moves, renames, copies and creation in the current
explorer session. **Ctrl+Z / Ctrl+Y** targets file history only while the sidebar
has focus; document and input-field history remain separate. Undoing creation
requires the same empty file or empty directory: it refuses to remove saved
content, a populated directory or a replaced entry. Undoing a copy requires its
complete tree to remain unchanged; Redo refuses a source changed since copying.
Copies larger than 10,000 entries still complete, but show an Undo-unavailable
warning and clear earlier file history to keep memory use bounded.
File history checks identities
and never overwrites an occupied destination. Permanent deletion and Trash cannot
be undone in TUInk and clear its earlier file history. Restore trashed entries
through the system Trash. Successful file Undo/Redo briefly shows a yellow notice
at the bottom of the sidebar; **Settings → File Undo/Redo notifications** disables
these success notices while retaining errors.

Deleting an open file keeps its text as an unnamed dirty draft requiring Save As.
A yellow banner beneath its document or Preview reports the removal and remains
until a successful file save or **Dismiss**. Editing, changing tabs, a failed or
cancelled save, and recovery checkpoints do not dismiss it.

TUInk checks the active document's file in the background, including while idle.
It checks metadata about once a second and rechecks content roughly every five
seconds when metadata stays unchanged, using bounded reads. Tab activation and
**Explorer → Refresh explorer** request a fresh check. These are periodic reads,
without an OS filesystem watcher; inactive documents are checked when activated.

**Settings → Auto reload unmodified files** is enabled by default. A file without
local edits reloads automatically, keeping the cursor and scroll within the new
text. Dirty documents keep their text and show a persistent yellow warning.
Turning the setting off makes all external updates manual.

The warning has no buttons. It explains **Update: File → Update from disk
(F1 → U)** and **Keep current text: File → Save (Ctrl+S)**. Saving the current
text over external changes requires explicit confirmation that those changes
will be destroyed. Cancelling, editing, or returning the disk to its earlier
bytes does not dismiss the warning; successful reload or explicit save does.
A further disk change after confirmation refuses the write and preserves the
draft. **Update from disk** remains available without a warning.

Update accepts disk text and format as one Undo step and marks that version
clean. Earlier local Undo history and historical gutter markers remain
available. Undo restores text and format while retaining the accepted disk
baseline. Live Preview follows its Source; a frozen Preview has its own
external Undo/Redo and supports the same update and confirmed-save commands.
Local drafts in retained Preview are protected from automatic reload. Successful
TUInk saves update the baseline without producing a false external warning.
Read/decoding failures and intervening edits retain the current text.

Explorer follows File in the top menu; F1–F7 retain their existing command
assignments. Search, Save As, document confirmations, notifications and status
are laid out inside the document pane. Opening these controls does not shorten
the neighbouring tree. Quit confirmation spans the full window.

Drag the divider to resize the panel. When the terminal is too narrow for both
regions, the explorer temporarily disappears; enlarging the terminal restores
it with the preferred width. **Explorer → Width** cycles 24, 30 and 40 columns.
**Explorer → Refresh explorer** reloads folder contents
after external changes. The first version has one root, no OS filesystem
watcher, and keeps explorer state only for the current run. Its loading uses the
same background scanning and bounded tree model as the file picker. File actions
refresh the affected directories; saving existing content does not rescan the tree.

Document reads and writes run in the background. During a save, the document
being written allows navigation and copying; text edits and closing that tab
wait until saving finishes or is cancelled. Other tabs remain usable. Esc cancels
file verification and preparation of the saved copy. Quit also cancels those
steps and abandons a pending open before checking unsaved work. Once the final
file update starts, quit waits up to five seconds for its outcome. A timeout
reports an unconfirmed save and attempts to retain the draft for recovery;
verify the target file before retrying. The timeout does not interrupt publication.
One document read or write runs at a time; keeping dialogs open in several tabs
does not start concurrent file operations. If you switch tabs during an operation,
its completion messages and prompts stay with the tab that started it.

In confirmation prompts, `Tab` / `Shift+Tab` cycle through the action buttons.
A click on the document permits inspection; Tab returns to the buttons. With a button focused, Left/Right move between buttons, and `Enter`
or Space activates the focused button. With the prompt focused, `Y` confirms
overwriting, saves edits in quit/close-tab confirmation, and restores
recovery snapshots. `N` chooses Discard in quit and close-tab confirmations,
without saving the affected documents. It cancels overwrite and encoding
confirmation; in recovery it chooses Later and keeps the offered snapshots for
the next launch.
The recovery prompt starts with Later focused. Only explicitly activating Delete
removes the offered snapshots; `D` activates it directly. Its hint is
`Y=Restore  D=Delete  N/Esc=Later  Ctrl+Q=Quit`. Restore opens the drafts in tabs;
Delete removes their recovery copies and leaves the original files unchanged.
Ctrl+Q keeps the offered recovery copies and starts normal application exit.
If current documents have unsaved edits, the usual Save/Discard/Cancel
confirmation appears; cancelling that exit preserves the edits and the offered
recovery copies.

`Ctrl+Q` has the following context-specific actions:

| Prompt | `Ctrl+Q` action |
|---|---|
| Save As | Cancel Save As |
| Overwrite confirmation | Cancel the save |
| Close-tab confirmation | Cancel closing the tab |
| Quit confirmation | Discard unsaved edits in all documents and quit |
| Recovery confirmation | Keep the offered snapshots and request normal application exit |

The first `Ctrl+Q` requests exit and shows the quit confirmation when needed.
Another delivered `Ctrl+Q` or `N` in that visible confirmation chooses **Discard**
or **Discard All**, without saving the files. Terminal key repeats count as
delivered keys too. If the complete question and action are unavailable, neither
shortcut can discard; `Esc` still cancels. The quit hint is
`Y/Ctrl+S=Save  N/Ctrl+Q=Discard  Esc=Cancel`, with the actual action labels for
multiple documents or Save As. The initial button remains Cancel; Tab, arrows
and Enter keep their normal button navigation and activation behavior.

`Esc` cancels Save As and overwrite, close-tab, or quit confirmation. In the
recovery prompt, `Esc` chooses Later and keeps the offered snapshots, including
when the terminal is too small to show the question and buttons. They remain
available after a normal quit and are offered again on the next launch.
Choosing Later also releases their ownership immediately, so another editor
instance can offer them while the current instance remains open.

Open Help > Keyboard hints for the complete in-app reference. Use Up/Down,
Page Up/Page Down, Home/End, or the mouse wheel over the dialog to scroll help.
Long lines wrap to the available width when the window is resized.
`Esc`, `Enter`, or Space closes the help window.

## Install a packaged release

### Debian package

The native Debian package requires glibc 2.34 or newer and zlib 1.2.2 or newer.
It does not require a JVM.

```bash
sudo apt install ./tuink_<version>_<deb-arch>.deb
tuink README.md
```

### Native Linux archive

```bash
tar -xzf tuink-<version>-linux-<arch>.tar.gz
cd tuink-<version>-linux-<arch>
./tuink README.md
```

The archive also includes `install.sh` and `uninstall.sh`. They manage only the
marked TUInk installation under `/opt/tuink` and its
`/usr/local/bin/tuink` link, and refuse to replace unrelated files.
The scripts require Bash, `sudo`, and GNU coreutils. Uninstall refuses unexpected
top-level entries and leaves the installation intact. Foreign files and directories
under `licenses/` are preserved while the managed installation files are removed.

### JVM ZIP

The portable ZIP requires Java 25 or newer.

```bash
unzip tuink-<version>.zip
cd tuink-<version>
./bin/tuink README.md
```

## Options and configuration

```text
Usage: tuink [options] [--] [file|directory]

  --no-mouse       Disable mouse tracking and mouse interactions
  --no-alt-screen  Do not use the alternate screen buffer
  --no-color       Render without ANSI colors
  --theme=<name>   Select blue, cyan (default), or monochrome
  --read           Start in reading mode
  --open-as=<encoding>
                   Choose auto, UTF-8, UTF-16LE/BE, Windows-1251, CP866 or KOI8-R
  --open-as-utf16le Interpret the selected BOM-marked file as UTF-16LE
  --profile        Log render timings
  --profile-file=<path>
                   Write render timings to a new file
  --file-uri=<uri> Open an ASCII percent-encoded absolute file URI
  --help, -h       Show command-line help
  --version        Show the application version
```

`--open-as` accepts `auto`, `utf-8`, `utf-16le`, `utf-16be`, `windows-1251`,
`cp866` and `koi8-r`; the separated form `--open-as cp866 file.txt` is also supported.
Without this option the editor detects the input encoding automatically.
The specific decoders require an existing file; `auto` keeps the ordinary open
behavior, including creation of a missing filename.

Help and version queries exit with status `0` without opening the terminal or
reading settings and recovery data. Invalid command-line arguments exit with
status `2` and write diagnostics to standard error.
Fatal startup or runtime failures exit with status `1` after terminal cleanup.
Diagnostics display terminal controls, embedded line breaks and bidi controls as
visible escapes, including in filenames and exception traces. This presentation
does not change the argument or filesystem path used by the editor.

UI settings are stored in `$XDG_CONFIG_HOME/tuink/tuink.properties`, defaulting
to `~/.config/tuink/tuink.properties` when the variable is unset, empty, or
relative. The selected profile also holds recovery data; TUInk does not read
another profile as a fallback. If the selected `XDG_CONFIG_HOME` or fallback
`user.home` contains `U+FFFD`, startup stops with a diagnostic: Java may have lost
the original Unix filename bytes. This also conservatively rejects a literal
`U+FFFD` in that directory name. Set `XDG_CONFIG_HOME` to an unambiguous absolute
directory to start; no other profile is selected automatically. The `--read`,
`--no-mouse`, and `--theme` options apply only to the current session. Explicit
changes through the menus or their key bindings are saved as preferences.
Project-search defaults use optional `search.*` properties in this same file;
their values and limits are documented in the [project search guide](docs/project-search.md#defaults-and-limits).
Readable unsupported settings open a **Fix / Delete / Later** confirmation,
initially focused on **Later**. **Fix** keeps valid fields of the current
format and replaces invalid or missing values with defaults; an unsupported
whole format or malformed properties file is replaced with defaults. **Delete**
removes the selected profile entry and uses defaults. Neither action converts
old formats or accepts old names. **N/Esc** or the default button leaves the
file unchanged and makes preference changes temporary for this session.
**Ctrl+Q** requests normal application exit while keeping the file and any
offered recovery copies. Recovery is offered after the settings response.
If the settings file changed externally, Fix and Delete preserve the changed
file and keep the confirmation open with an error.
If the settings file changes during the session, including edits saved from
TUInk itself, the changed file is kept and the application warns on exit that
this session's menu preferences were not saved.
Concurrent TUInk instances coordinate document and settings writes with persistent
locks in `~/.local/state/tuink/save-locks-v1`, independently of configuration profiles.
The lock covers the physical directory entry, including symbolic-link aliases,
and the final baseline check and replacement. A busy or unavailable lock leaves
the draft unsaved and preserves the target. Lock files stay in place to retain
one shared lock inode; they are not created inside edited projects.
This coordination applies to cooperating TUInk processes for the same user on
one host. Other editors do not acquire these locks and can still race a save.
If the settings file is a symbolic link, saving updates its existing regular-file
target and preserves the link.
Recovery data is stored in the selected profile's `tuink/recovery` directory
(`~/.config/tuink/recovery` by default); on POSIX systems the directory uses
`0700` permissions and snapshot files use `0600`.
Active recovery snapshots are isolated between editor instances. If input ends
or the editor is interrupted, its snapshots remain available for recovery on
the next start.
Recovery retains current text, file format and the accepted disk baseline,
including unsaved frozen Preview text after external changes. Undo/Redo history
and the original text used for historical gutter markers belong to the current
session and are not stored in recovery.
Autosave writes run serially in the background with at most one snapshot in
flight. The UI captures immutable text blocks; the worker assembles and writes
the snapshot. Shutdown waits for that write before final recovery saves or cleanup.
Startup lists snapshots before reading their text. Restore preserves files already
opened in the session; a snapshot that exceeds available memory stays on disk and
can be postponed or explicitly deleted.

Postponed recovery is available through **File > Recover drafts…** without
restarting. Discovery skips checkpoints owned by this session and other running
editors; incompatible snapshots remain untouched.

<details>
<summary>Supported syntax-highlighting formats</summary>

TUInk detects syntax by extension for Java, C/C++ headers/sources, Arduino, Python, Markdown,
JavaScript, TypeScript, Gradle, shell scripts, SQL, Go, Rust, PHP, Ruby, Perl,
Kotlin, Protocol Buffers, HTML, CSS/SCSS, diff/patch, CSV, JSON, YAML, TOML,
INI/properties, and XML.

It also recognizes `Dockerfile`, `.env`, `.gitignore`, `Makefile`,
`.editorconfig`, Docker Compose files, and Git commit-message files by name.

</details>

## Build and release

Run the full JVM test and build suite:

```bash
./gradlew clean check build
```

The main distribution commands are:

| Command | Output |
|---|---|
| `./gradlew :app:dist` | JVM directory with launchers, libraries, README, documentation and licenses |
| `./gradlew :app:distZip` | The same JVM distribution in a ZIP |
| `./gradlew :app:nativeDist` | A bare glibc executable in `build/native-dist/glibc/tuink` |
| `./gradlew :app:nativeDistTarGz` | A native archive with README, documentation, licenses and install scripts |

Both native commands accept `-Pmusl` for static musl and `-Ppgo` for a
profile-optimized build; combine them for musl PGO. Without either flag, they
build ordinary glibc. The bare binary omits accompanying files; use the archive
to distribute the complete package.

Native releases use the pinned Oracle GraalVM toolchain. Follow the
[native toolchain instructions](docs/building.md#native-toolchain), then run:

```bash
export GRAALVM_HOME=/absolute/path/to/graalvm-jdk-25.0.4+7.1
export JAVA_HOME="$GRAALVM_HOME"
export PATH="$JAVA_HOME/bin:$PATH"
./gradlew clean :app:release
```

The release task builds the tested JVM ZIP, glibc archive, Debian package, and
SHA-256 manifest under `build/releases/`. It runs a short render benchmark
for each shipped native variant. The task does not install or publish anything.

Optional [PGO training](docs/building.md#pgo) specifically requires **Oracle
GraalVM 25.0.4+7.1 with Native Image**; Oracle JDK alone is insufficient.
After training both libc profiles,
`./gradlew :app:distributionArchives -Ptarget=all -Ppgo` packages
all five variants: JVM ZIP, native glibc, native musl, glibc PGO, and musl PGO.
The default release uses ordinary Oracle O3 binaries; add `-Ppgo` to include
the selected PGO archives and use PGO in the Debian package. `nativeDeb -Ppgo`
also builds that package separately; its name and version match the ordinary
package, so the two replace each other. Musl-only releases omit Debian packaging.
Packaging requires existing profiles and never starts training automatically.

`perfSmoke` and `perfCheck` accept the same `-Pmusl` and `-Ppgo` flags to measure
the selected native variants. See the [build reference](docs/building.md) for
training, checksums and advanced settings.

## Project family

| Project | Purpose | Status |
|---|---|---|
| **TUInk** | Non-modal terminal text editor | Functional application |
| **TUIframe** | Reusable terminal UI runtime and widgets | Internal, incubating API |

TUIframe is split into `tuiframe-core`, `tuiframe-jline`, `tuiframe-widgets` and
`tuiframe-explorer`. The explorer module owns filesystem browsing, drag gestures,
file operations, guarded session history and the Trash provider. Each instance
has read-only or file-management access; TUInk enables management only in its
sidebar. TUInk retains document bindings, editor commands and application policy.
Reusable [controls](docs/controls.md) include inverse text fields, check buttons,
typed dropdowns and tool palettes; their behavior and default styles live in TUIframe.

## Repository layout

Applications own their frontend code, packaging templates, and sample documents.
Shared editor libraries and the general terminal toolkit live separately:

```text
apps/
  tuink/
    launcher/               # :app — CLI entrypoint and distribution assembly
    terminal-ui/            # :tui — editor windows, commands, and dialogs
    terminal-rendering/     # :render — editor text layout and rendering plans
    packaging/              # TUInk installers, package templates, and checks
    samples/                # documents for demonstrating the editor
libs/
  editor/
    api/                    # :editor-contract
    engine/                 # :core
    search-text/            # :editor-search-text — shared matcher and budgets
    session/                # :editor-session
    storage-jvm/            # :editor-storage-jvm
    diff-jgit/              # :editor-diff-jgit
    highlight-regex/        # :highlight-regex
  project/
    search/                 # :project-search — portable search over text sources
    search-jvm/             # :project-search-jvm — filesystem and ignore adapter
  markdown/
    preview/                # :markdown-preview — portable models and contracts
    commonmark/             # :markdown-commonmark — Markdown parser adapter
    math/                   # :markdown-math — bounded TeX subset
    mermaid/                # :markdown-mermaid — bounded diagram subset
  tuiframe/
    core/                   # :tuiframe-core
    jline/                  # :tuiframe-jline
    widgets/                # :tuiframe-widgets
    explorer/               # :tuiframe-explorer — filesystem UI and operations
  unicode-text/             # :unicode-text
verification/
  ui/                       # whole-application terminal scenarios
  performance/              # rendering benchmarks and PGO training
  compatibility/            # external JVM and Android toolchain consumers
  fixtures/                 # shared test implementations
docs/                       # guides, architecture decisions, and published media
licenses/                   # third-party notice bundles for distributions
gradle/                     # wrapper, dependency verification, and build scripts
```

The comments identify the Gradle project paths, which remain independent of
physical directories through `settings.gradle`. Existing commands such as
`./gradlew :app:run` and `./gradlew :core:test` are unchanged. Module tests stay
in their module's `src/test`; `verification/` holds checks shared across modules
or exercising an application as a whole. See [Contributing](CONTRIBUTING.md#architecture)
for placement and dependency rules.

Generated output stays in root or module `build/` directories. `.gradle/` is a
local cache; ignored `.local/` holds working notes, toolchains, profiles, and
diagnostic material. These local directories are outside the source layout.

## Development and documentation

- [Перенос на другой компьютер, сборка и запуск (русский)](docs/setup-ru.md)
- [Build, test, and native targets](docs/building.md)
- [Release archives and Debian packaging](docs/packaging.md)
- [TUInk 0.19.0 release notes](docs/releases/0.19.0.md)
- [TUInk 0.18.0 release notes](docs/releases/0.18.0.md)
- [Markdown preview (русский)](docs/markdown-preview.md)
- [Architecture](docs/architecture-roadmap.md)
- [Architecture decisions](docs/adr/)
- [Compatibility policy](docs/compatibility.md)
- [Render-loop profiling](docs/profiling.md)
- [Render benchmarks](verification/performance/README.md)
- [Security policy](SECURITY.md)
- [Contributing](CONTRIBUTING.md)

## License

TUInk's source code is licensed under the Apache License 2.0; see
[LICENSE](LICENSE). Native distributions also contain Oracle GraalVM runtime
components subject to Oracle's GFTC and separate third-party terms. See the
[Oracle runtime notices](licenses/oracle-graalvm-25.0.4/README.md) and
[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
