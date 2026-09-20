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
- **Choose how to run it.** Use Java 17, a native Linux archive, or a Debian
  package.

## Try it

TUInk needs Java 17 or newer and an interactive terminal.

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

### File safety

- Save As validation and overwrite confirmation;
- explicit handling of read-only files and dirty tabs;
- recovery snapshots for unfinished edits;
- preservation of detected charset, BOM, line-separator layout, and final-EOL
  state;
- preservation of existing POSIX permission bits;
- safe saving through symbolic links without replacing the link itself.

## Controls

| Input | Action |
|---|---|
| `Ctrl+O` | Open the tree-based file picker |
| `Ctrl+S` | Save the current file |
| `Ctrl+K` | Save As |
| `Ctrl+N` | Open a new tab |
| `Ctrl+Tab` / `Ctrl+PageDown` | Select the next tab |
| `Ctrl+PageUp` | Select the previous tab |
| `Ctrl+F` | Open search and replace |
| `Ctrl+Z` / `Ctrl+Y` | Undo / redo |
| `Ctrl+/` | Toggle line comments |
| `Ctrl+Q` | Quit, with confirmation for unsaved work |
| `F1` … `F6` | Open File, Edit, View, Tab, Settings, and Help menus |
| Arrows / Page Up / Page Down / Home / End | Navigate the document |
| `Shift` + Arrow keys | Extend the selection |

Mouse controls include click-to-position, drag selection, Shift+Click
selection from the previous cursor position, wheel scrolling, tab and menu
activation, scrollbar interaction, and file-picker navigation.

In search mode, use `Enter` or `Ctrl+F` for the next match, `Ctrl+G` for the
previous match, `Ctrl+R` to replace, and `Ctrl+A` to replace all. `Tab` moves
between the fields and search options.

## Install a packaged release

### Debian package

The native Debian package requires glibc 2.34 or newer and zlib 1.1.4 or newer.
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

The portable ZIP requires Java 17 or newer.

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
                   Write render timings to a file
  --help, -h       Show command-line help
```

UI settings are stored in `~/.config/tuink/tuink.properties`. Recovery data is
stored under `~/.config/tuink/recovery`; on POSIX systems the directory uses
`0700` permissions and snapshot files use `0600`.

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

Official native releases use a pinned GraalVM Community toolchain. Follow the
[native toolchain instructions](docs/building.md#native-toolchain), then run:

```bash
export GRAALVM_HOME=/absolute/path/to/graalvm-community-25.2.4+7.1
./gradlew clean :app:release
```

The release task builds the tested JVM ZIP, glibc archive, Debian package, and
SHA-256 manifest under `build/releases/`. It also runs a short native render
benchmark. The task does not install or publish anything.

## Project family

| Project | Purpose | Status |
|---|---|---|
| **TUInk** | Non-modal terminal text editor | Functional application |
| **TUIframe** | Reusable terminal UI runtime and widgets | Internal, incubating API |

TUIframe is split into `tuiframe-core`, `tuiframe-jline`, and
`tuiframe-widgets`. TUInk deliberately keeps editor-specific state and policy
outside those reusable modules.

## Development and documentation

- [Build, test, and native targets](docs/building.md)
- [Release archives and Debian packaging](docs/packaging.md)
- [Architecture decisions](docs/adr/)
- [Compatibility policy](docs/compatibility.md)
- [Render-loop profiling](docs/profiling.md)
- [Render benchmarks](verification/performance/README.md)
- [Security policy](SECURITY.md)
- [Contributing](CONTRIBUTING.md)

## License

TUInk is distributed under the Apache License 2.0. See [LICENSE](LICENSE) and
[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
