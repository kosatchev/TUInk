# TUInk

Minimal Java terminal text editor with clean multi-module architecture:

- `core`: editor engine and text model (`TextBuffer` + `SimpleLineBuffer`)
- `render`: UI-agnostic render planning and style spans
- `highlight-regex`: regex-based syntax highlighter
- `tui`: JLine-based terminal frontend (raw mode + alternate screen + ANSI diff)
- `app`: canonical entrypoint and GraalVM native build configuration

## Requirements

- Java 17
- Gradle wrapper (`./gradlew`)

## Build

```bash
./gradlew build
```

## Run

```bash
./gradlew :app:run
```

Run with file and flags:

```bash
./gradlew :app:run -PappArgs="file.txt"
./gradlew :app:run -PappArgs="--no-mouse file.txt"
./gradlew :app:run -PappArgs="--no-alt-screen file.txt"
./gradlew :app:run -PappArgs="--no-color file.txt"
./gradlew :app:run -PappArgs="--theme=light file.txt"
./gradlew :app:run -PappArgs="--read file.txt"
./gradlew :app:run -PappArgs="--help"
```

## Native Build

```bash
./gradlew :app:nativeCompile
./gradlew :app:nativeMuslCompile
```

Default binary names:
- `tuink`
- `tuink-musl`

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
- `Ctrl+E`: open `Edit` menu, `F10`: open `File` menu (`←/→`, `↑/↓`, `Enter`, `Esc`)
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
- UI settings are stored in `~/.config/tuink.properties`.

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
