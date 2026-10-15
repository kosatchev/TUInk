# TUInk

> **A familiar text editor that lives in your terminal.**

TUInk is a non-modal editor for people who want to stay in the terminal
without giving up familiar controls. Open a file and start typing: use ordinary
keyboard shortcuts, the mouse, menus, tabs, search and replace, syntax
highlighting, and a tree-based file picker without learning an editor command
language.

## TUInk in action

### Files and menus

![TUInk file picker and menu tour](docs/assets/tuink-menus.gif)

### Tabs and syntax highlighting

![TUInk tab tour](docs/assets/tuink-tabs.gif)

## Why TUInk?

- **Start editing immediately.** TUInk is non-modal and uses conventional
  shortcuts for opening, saving, searching, copying, undoing, and redoing.
- **Use keyboard and mouse together.** Click, drag, Shift+Click, menus,
  scrollbars, and keyboard navigation work side by side.
- **Work with real-world Unicode.** Cursor movement and selection respect
  extended grapheme clusters, including emoji, combining characters, and wide
  CJK text.
- **Find and navigate quickly.** Work in multiple tabs, browse files in a tree,
  search across every tab, and replace matches in the active tab. Case,
  whole-word, and wildcard options help narrow the result.
- **Edit code comfortably.** TUInk provides syntax highlighting, line and word
  wrapping, auto-indent, autopairs, automatic lists, visible whitespace,
  rainbow brackets, and line-comment toggling.
- **See what changed.** Gutter markers distinguish edits since the last save
  from edits since the file was opened.
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

On Linux, saving existing files also requires the `acl` package
(`getfacl` and `setfacl`), for both JVM and native distributions. The Debian
package installs this dependency automatically.

```bash
./gradlew --console=plain :app:run
```

Open a file directly:

```bash
./gradlew --console=plain :app:run -PappArgs="README.md"
```

Run `./gradlew buildHelp` for the complete local build command reference.

## Highlights

### Familiar editing

- multiple tabs with mouse and keyboard navigation;
- a shared application clipboard for copying, cutting, and pasting between tabs;
- selection by keyboard, mouse drag, or Shift+Click;
- undo and redo;
- search across all tabs, with replacement in the active tab;
- menus, buttons, dialogs, text fields, trees, and scrollbars;
- configurable Cyan, Blue, and Light themes.

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

For heavily changed documents, gutter markers can use a broader changed range
to keep editing responsive; unchanged lines inside that range may also be marked.

### File safety

- Save As validation and overwrite confirmation;
- explicit handling of read-only files and dirty tabs;
- recovery snapshots for unfinished edits;
- preservation of detected charset, BOM, line-separator layout, and final-EOL
  state;
- preservation of existing POSIX permissions, owner/group, user-defined extended
  attributes, Linux POSIX access ACLs, and ACLs exposed by the filesystem provider;
- safe saving through symbolic links without replacing the link itself.

If an edit leaves an empty line between bare CR and LF endings, that empty
line's ending becomes CRLF so saving and reopening retain both line breaks.

Normal saves compare the destination with the bytes last opened or saved.
A change detected during these checks retains your edits and reports a
conflict. Use Save As with a different name to keep both versions, or reopen
the file. This check also applies to recovered documents. Locks coordinate
TUInk instances of the same user across configuration profiles. Other editors
do not use these locks: a change after the final check can still be overwritten.

Existing-file saves copy supported filesystem attributes to the staged file
before replacing the original. If those attributes cannot be preserved, saving
fails and the original is retained. Atomic replacement creates a new inode;
other hard links keep their previous contents.

Where the filesystem exposes Unix mode bits, saves also preserve `setuid`,
`setgid`, and `sticky`, including after content edits. The staged mode is
verified before replacement; a filesystem that clears a required bit causes
the save to fail while retaining the original.

Linux access ACLs are copied with their named entries and exact mask, then
verified before replacement. If the ACL tools are unavailable or fail, saving
reports an error and retains the original file.

## Controls

| Input | Action |
|---|---|
| `Ctrl+O` | Open the tree-based file picker |
| `Ctrl+S` | Save the current file |
| `Ctrl+K` / `Ctrl+W` / `Ctrl+Shift+S` / `Ctrl+Shift+F4` | Save As |
| `Ctrl+X` / `Ctrl+C` / `Ctrl+V` | Cut / copy / paste using the application clipboard |
| `Ctrl+A` | Select all text in the focused document or text field |
| `Ctrl+Z` / `Ctrl+Y` | Undo / redo |
| `Ctrl+N` | Open a new tab |
| `Ctrl+Tab` / `Super+Tab` / `Ctrl+PageDown` | Select the next tab |
| `Ctrl+PageUp` | Select the previous tab |
| `Ctrl+F` | Open search and replace |
| `Ctrl+/` | Toggle line comments |
| `Ctrl+Q` | Quit, with confirmation for unsaved work |
| `F1` / `Alt+I` | Open the File menu |
| `F2` / `Alt+E` | Open the Edit menu |
| `F3` / `Alt+V` | Open the View menu |
| `F4` / `Alt+T` | Open the Tab menu |
| `F5` / `Alt+S` | Open the Settings menu |
| `F6` / `Alt+H` | Open the Help menu |
| Arrows / Page Up / Page Down / Home / End | Navigate the document |
| `Shift` + Arrow keys | Extend the selection |
| `Ctrl+Left` / `Alt+B` | Move to the previous word |
| `Ctrl+Right` / `Alt+F` | Move to the next word |
| `Enter` / `Tab` | Insert a newline / tab |
| `Backspace` / `Delete` | Delete backward / forward |

Modified shortcuts work when the terminal sends distinct key sequences and the
desktop passes them through. For example, some terminals cannot distinguish
`Ctrl+Shift+S` from `Ctrl+S`, or `Ctrl+Tab` from `Tab`. Use another listed
shortcut when a combination is unavailable.

Mouse controls include click-to-position, drag selection, Shift+Click
selection from the previous cursor position, wheel scrolling, tab and menu
activation, scrollbar interaction, and file-picker navigation.

Saving and copying preserve a manually scrolled document viewport. Each tab
remembers its own viewing position; navigation and editing resume following
the text cursor. Resizing wrapped text preserves the text position at the top
of the viewport. Editing commands rejected in reading mode leave the viewing
position unchanged. Keyboard input ends an active mouse drag before handling
the key.

With line wrapping enabled, Up/Down and Page Up/Page Down navigate displayed
rows; Shift+Up/Down extend the selection by displayed rows. A fully filled
final wrap segment has an extra empty row for the cursor after the text.

Without a selection, `Ctrl+C` copies the current line and `Ctrl+X` cuts it.
Close a tab through Tab > Close Tab.

With the search panel focused, use `Ctrl+F` for the next match, `Ctrl+G` for the previous
match and `Ctrl+R` to replace. Activate the All button to replace all matches
in the active tab.
`Enter` advances to the next match from a text field. Enter or Space activates
the focused button, including option toggles. `Tab` and
`Shift+Tab` cycle through fields, options, buttons, and the document; `Esc`
closes search. Clicking a search control or the document also moves focus.
With the document focused, ordinary editing and `Ctrl+Z` / `Ctrl+Y` work
while search stays open; `Tab` continues to move focus. Undo and redo are
ignored throughout the search panel. `Ctrl+F` returns focus to Find.

With Find or Replace focused, Left/Right and Home/End move within the field;
`Ctrl+Left/Right` or `Alt+B/F` move by word. Hold Shift to select text, or use
`Ctrl+A` to select the entire field. `Ctrl+C` copies the selection and `Ctrl+X`
cuts it; without a selection both leave the field and clipboard unchanged.
Dragging the mouse selects field text; Shift+Click extends the selection.
Typing and `Ctrl+V` replace selected text, joining pasted lines with spaces.
`Ctrl+Backspace/Delete` delete by word. Up/Down stay in the single-line field;
use `Tab` / `Shift+Tab` or a click to move focus. Page Up/Page Down and the mouse
wheel scroll the document while preserving its caret, selection, and field focus.
These field shortcuts leave the document text unchanged.
`Ctrl+C` / `Ctrl+X` / `Ctrl+V` are ignored when a button or search option is focused.

Save As and confirmation prompts include the document in their `Tab` /
`Shift+Tab` focus cycle. While either is open, the document allows navigation,
selection, copying, and scrolling. Attempts to edit, cut, paste, undo, or redo
the document briefly highlight the dialog and focus its first available
control; the rejected input is discarded. Save As keeps its field editable
and uses the same field clipboard behavior as Find and Replace.
Its focus order is filename, Save, Cancel, then document. `Enter` in the filename
field saves; `Enter` or Space activates a focused button. Cancel closes the prompt
without saving, just like `Esc`. Buttons are hidden in narrow terminals when there
is not enough room to retain an editable filename field; `Enter` and `Esc` still work.
A failed save keeps the prompt open and returns focus to the filename. Its error
stays visible until the filename changes or the prompt closes. Submitting an empty
filename shows an error and preserves any pending quit or close-tab operation.

Help and the file picker keep focus inside the dialog and block all document
input, including selection, copying, and scrolling. `Tab` / `Shift+Tab` cycle
only through their controls. Input aimed at the document briefly highlights
the dialog, focuses its first available control, and is discarded.

In the file picker, `Ctrl+H` toggles hidden files. With the tree focused,
`Backspace` moves its root to the parent directory, Left/Right collapse or
expand folders, and `Shift+Left` / `Shift+Right` scroll long names horizontally.
`Enter` opens a selected file or makes a selected directory the root. `Tab`
and `Shift+Tab` move between the tree, OK, and Cancel; `Esc` closes the picker.
Enter or Space activates OK and Cancel when focused.
In terminals where Backspace sends `^H`, legacy `Ctrl+H` is interpreted as
Backspace because the two keys send the same byte.

In confirmation prompts, `Tab` / `Shift+Tab` cycle through buttons and the
document. With a button focused, Left/Right move between buttons, and `Enter`
or Space activates the focused button. With the prompt focused, `Y` confirms
overwriting, discards edits in quit/close-tab confirmation, and restores
recovery snapshots. `N` cancels overwrite, quit, or close-tab confirmation;
in recovery it discards and deletes the offered snapshots.

`Ctrl+Q` has the following context-specific actions:

| Prompt | `Ctrl+Q` action |
|---|---|
| Save As | Cancel Save As |
| Overwrite confirmation | Cancel the save |
| Close-tab confirmation | Cancel closing the tab |
| Quit confirmation | Discard unsaved edits and exit |
| Recovery confirmation | Discard and delete the offered recovery snapshots |

`Esc` cancels Save As and overwrite, close-tab, or quit confirmation. In the
recovery prompt, `Esc` also discards and deletes the offered snapshots.

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

### JVM ZIP

The portable ZIP requires Java 25 or newer.

```bash
unzip tuink-<version>.zip
./tuink-<version>/bin/tuink README.md
```

## Options and configuration

```text
Usage: tuink [options] [file]

  --no-mouse       Disable mouse tracking and mouse interactions
  --no-alt-screen  Do not use the alternate screen buffer
  --no-color       Render without ANSI colors
  --theme=<name>   Select blue, cyan (default), or light
  --read           Start in reading mode
  --profile        Log render timings
  --profile-file=<path>
                   Write render timings to a new file
  --help, -h       Show command-line help
```

Help exits with status `0`. Invalid command-line arguments exit with status `2`
and write diagnostics to standard error.

UI settings are stored in `~/.config/tuink/tuink.properties`. Recovery data is
stored under `~/.config/tuink/recovery`; on POSIX systems the directory uses
`0700` permissions and snapshot files use `0600`.
Active recovery snapshots are isolated between editor instances. If input ends
or the editor is interrupted, its snapshots remain available for recovery on
the next start.

<details>
<summary>Supported syntax-highlighting formats</summary>

TUInk detects syntax by extension for Java, C headers/sources, Arduino, Python, Markdown,
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

Native releases use the pinned Oracle GraalVM toolchain. Follow the
[native toolchain instructions](docs/building.md#native-toolchain), then run:

```bash
export GRAALVM_HOME=/absolute/path/to/graalvm-jdk-25.0.4+7.1
export JAVA_HOME="$GRAALVM_HOME"
export PATH="$JAVA_HOME/bin:$PATH"
./gradlew clean :app:release
```

The release task builds the tested JVM ZIP, glibc archive, Debian package, and
SHA-256 manifest under `build/releases/`. It also runs a short native render
benchmark. The task does not install or publish anything.

Optional [PGO training](docs/building.md#pgo) uses the same SDK and produces a
separate local binary; release artifacts use the ordinary Oracle O3 build.

## Project family

| Project | Purpose | Status |
|---|---|---|
| **TUInk** | Non-modal terminal text editor | Functional application |
| **TUIframe** | Reusable terminal UI runtime and widgets | Internal, incubating API |

TUIframe is split into `tuiframe-core`, `tuiframe-jline`, and
`tuiframe-widgets`. TUInk deliberately keeps editor-specific state and policy
outside those reusable modules.

## Development and documentation

- [Перенос на другой компьютер, сборка и запуск (русский)](docs/setup-ru.md)
- [Build, test, and native targets](docs/building.md)
- [Release archives and Debian packaging](docs/packaging.md)
- [Architecture roadmap](docs/architecture-roadmap.md)
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
