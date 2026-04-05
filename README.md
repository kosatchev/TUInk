# TUInk

Minimal Java terminal text editor with clean multi-module architecture:

- `core`: editor engine and text model (`TextBuffer` + `SimpleLineBuffer`)
- `render`: UI-agnostic render planning and style spans
- `highlight-regex`: regex-based syntax highlighter
- `tui`: JLine-based terminal frontend (raw mode + alternate screen + ANSI diff)
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

Build the tested JVM ZIP, glibc archive, Debian package, and SHA-256 manifest:

```bash
./gradlew clean :app:release
```

Artifacts are written to `build/releases/`.

Install the Debian package:

```bash
sudo apt install ./build/releases/tuink_<version>_<arch>.deb
```

Or extract the native archive and use its `install.sh`.

Detailed documentation:

- Print the local build command reference with `./gradlew buildHelp`.
- [Build and native targets](docs/building.md)
- [Release archives and Debian packaging](docs/packaging.md)
- [Render-loop profiling](docs/profiling.md)

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
- `Save As` validates target path and asks overwrite confirmation when file exists (`y/N`).
- `Save As`/overwrite prompt is shown in a separate bar above status bar (red).
- all save failures are explained in the save notification bar (read-only file, no access to directory, invalid target path, etc).
- non-interactive save notifications are shown in a separate bar above status: green for success, red for errors, auto-hide after ~3s.
- save writes atomically via temporary file + move.
- UI settings are stored in `~/.config/tuink/tuink.properties`.

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
