# TUInk

Minimal Java terminal text editor with clean multi-module architecture:

- `unicode-text`: dependency-free grapheme boundaries and UTF-16 offset mapping
- `tui-core`: editor-independent terminal text, cells, geometry, input, scrolling,
  frame, capability, and event-loop contracts under `dev.tuink.terminal.*`
- `tui-jline`: JLine lifecycle, composed terminal runtime, physical input
  decoder, capability detection, and ANSI screen presentation
- `tui-widgets`: editor-independent passive layout/theme contracts plus
  reusable `Button`, `Modal`, and Unicode-aware `TextField` primitives
- `core`: editor engine and text model (`TextBuffer` + `SimpleLineBuffer`)
- `render`: editor render planning, style spans, and terminal-aware layout
- `highlight-regex`: regex-based syntax highlighter
- `tui`: TUInk-specific widgets, controllers, layout, and editor rasterization
- `app`: canonical entrypoint and release assembly

## Project Status

This project is currently feature-first: new capabilities sometimes arrive before the dust from recent changes has settled.
The editor is functional, cleanup is ongoing, and a larger refactor is planned once the core feature set stabilizes.

## Samples

Repository contains syntax and content samples in `apps/tuink/samples/`.

These files are useful for:

- quick manual checks of syntax highlighting
- scroll/performance smoke checks on medium-size files
- markdown fenced-code rendering checks

## Gutter Diff

- Переключаемые режимы в меню `View`: `Disabled`, `Histogram`, `Myers`, `Stable`.
- Два слоя маркеров:
- `saved -> current` показывается обычными цветными маркерами.
- `open -> current` показывается серыми маркерами.
- Режим сохраняется в `~/.config/tuink/tuink.properties` (`ui.gutterDiffMode`).

## Requirements

- Java 17
- Gradle wrapper (`./gradlew`)
- GraalVM with `native-image` and a C toolchain for native releases
- Python 3.10+ on a POSIX system for render benchmarks and complete releases

## Build and test

```bash
./gradlew clean test build
```

## Run

Run the canonical `app` module from the repository root:

```bash
./gradlew --console=plain :app:run
```

Run from an interactive TTY terminal. Non-interactive consoles may fall back to a dumb terminal and fail.

Run with file and flags:

```bash
./gradlew --console=plain :app:run -PappArgs="file.txt"
./gradlew --console=plain :app:run -PappArgs="--no-mouse file.txt"
./gradlew --console=plain :app:run -PappArgs="--no-alt-screen file.txt"
./gradlew --console=plain :app:run -PappArgs="--no-color file.txt"
./gradlew --console=plain :app:run -PappArgs="--theme=light file.txt"
./gradlew --console=plain :app:run -PappArgs="--read file.txt"
./gradlew --console=plain :app:run -PappArgs="--profile --profile-file=profile-jvm.log file.txt"
./gradlew --console=plain :app:run -PappArgs="--help"
```

## Release artifacts

Build the tested JVM ZIP, glibc archive, Debian package, and SHA-256 manifest.
The release task also runs the short native render benchmark:

```bash
./gradlew clean :app:release
```

Artifacts are written to `build/releases/`.

Install the Debian package:

```bash
sudo apt install ./build/releases/tuink_<version>_<arch>.deb
```

Or extract the native archive and use its `install.sh`.
The archive scripts manage `/opt/tuink` and `/usr/local/bin/tuink`; they refuse
to replace unrelated files or remove unrecognized directory contents.

Detailed documentation:

- Print the local build command reference with `./gradlew buildHelp`.
- [Build and native targets](docs/building.md)
- [Release archives and Debian packaging](docs/packaging.md)
- [Render-loop profiling](docs/profiling.md)
- [Render benchmarks](verification/performance/README.md)
- [Compatibility policy](docs/compatibility.md)
- [Contributing](CONTRIBUTING.md)

## MVP keybindings

- `Ctrl+Q`: quit (asks confirmation if dirty)
- `Ctrl+O`: open file dialog (scrollable tree view)
- `Ctrl+S`: save
- `Ctrl+K`: save as (recommended, terminal-safe)
- `Ctrl+Shift+S`: save as (if terminal passes it through)
- Arrows: move cursor
- `PageUp` / `PageDown`: scroll by page
- `Home` / `End`: line start/end
- `Backspace` / `Delete`: edit
- `Enter`: insert newline
- Typing: insert character
- `Ctrl+F`: search mode (`Esc` to return)
- `Ctrl+/`: toggle line comments on the current line or selected lines
- Menu shortcuts: `F1` = `File`, `F2` = `Edit`, `F3` = `View`, `F4` = `Tab`, `F5` = `Settings`, `F6` = `Help` (`Alt+I/E/V/T/S/H` also works); navigation with `←/→`, `↑/↓`, `Enter`, `Esc`
- Mouse:
- left click `File`/`Edit` in top bar to open/close corresponding menu
- left click menu item to execute it
- left click in text area to move cursor
- hold left mouse button and drag in text area to select text
- in `Find` panel, left click `Find/Replace` fields to change focus; click toggles (`Аа`, `word`, `*`) to switch options
- mouse wheel scrolls the document
- in open file dialog, item click only selects; click folder arrow to expand/collapse; double click item opens file / toggles folder / goes up on `..`; open selected file with `OK`; `Cancel` closes dialog
- in search mode:
- `Enter`/`Ctrl+F` next, `Ctrl+G` previous, `Ctrl+R` replace, `Ctrl+A` replace all
- `Tab` move focus between `Find`, `Replace`, and option toggles
- `Space` toggles focused option (`i`, `word`, `*`)
- visible matches are highlighted in the editor; current selected match is highlighted stronger

## Save behavior

- `Ctrl+S` saves current file and shows confirmation in status bar.
- if current file is read-only, editor opens `Save As` prompt with suggested new name.
- closing one modified unnamed/read-only tab offers `Save As` and closes it after a successful save.
- quitting with multiple modified tabs lists their names and offers `Save All`, `Discard All`, or `Cancel`; `Save All` opens `Save As` in turn for tabs that need a path.
- automatic `Save As` suggestions skip existing files by adding a numeric suffix.
- `Save As` validates target path and asks overwrite confirmation when file exists (`y/N`).
- `Save As`/overwrite prompt is shown in a separate bar above status bar (red).
- all save failures are explained in the save notification bar (read-only file, no access to directory, invalid target path, etc).
- non-interactive save notifications are shown in a separate bar above status: green for success, red for errors, auto-hide after ~3s.
- save writes through a private temporary file and move; replacement is atomic
  when the filesystem supports atomic move, while the portable fallback makes
  no crash-atomicity claim.
- existing POSIX permission bits are preserved, including executable bits.
- saving through a symbolic link updates its resolved target without replacing the link.
- an atomic replacement gives the saved path a new inode; other hard links keep their previous content.
- UI settings are stored in `~/.config/tuink/tuink.properties`. Only the
  current `format=1` schema is accepted; an incompatible file is left unchanged
  and the editor starts with defaults after showing a warning.
- recovery snapshots are stored under `~/.config/tuink/recovery`; on POSIX
  systems the directory is restricted to `0700` and snapshot files to `0600`.
  Only current `format=4` snapshots are restored; they retain charset, BOM,
  final-EOL state, mixed line separators, and create-vs-replace save intent. A
  generation-specific text file is committed by one metadata replacement. On
  filesystems with atomic move, an interrupted editor process cannot combine
  old metadata with new text; the fallback makes no power-loss guarantee. Incompatible snapshots are
  reported and left unchanged. A symlink in place of the recovery directory
  is rejected.

## Status indicators

Status bar shows mini indicators:

- `RO`/`RW` (read-only/read-write)
- `ENC:<charset>`
- `INS`

## Syntax highlighting

Auto-detected by file extension for:

- `java`
- `c`, `h`, `ino`
- `py`
- `md`, `markdown`
- `js`, `mjs`, `cjs`, `jsx`
- `ts`, `tsx`
- `gradle`
- `sh`, `bash`, `zsh`
- `sql`
- `go`
- `rs`
- `php`, `phtml`, `php5`, `php7`, `php8`
- `rb`, `rake`, `gemspec`
- `pl`, `pm`, `perl`, `t`
- `kt`, `kts`
- `proto`
- `html`, `htm`
- `css`, `scss`
- `diff`, `patch`
- `csv`
- `json`
- `yaml`, `yml`
- `toml`
- `ini`, `cfg`, `conf`, `properties`
- `xml`

Also auto-detected by file name for:

- `Dockerfile`
- `.env` (including `.env.*`)
- `.gitignore`
- `Makefile`
- `.editorconfig`
- `docker-compose.yml`, `docker-compose.yaml`
- `git-commit*`

## License

- Project license: Apache License 2.0 (see `LICENSE`)
- Third-party notices: `THIRD_PARTY_NOTICES.md`
