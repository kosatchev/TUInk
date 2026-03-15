# TUInk

Minimal Java terminal text editor with clean multi-module architecture:

- `core`: editor engine and text model (`TextBuffer` + `SimpleLineBuffer`)
- `render`: UI-agnostic render planning and style spans
- `highlight-regex`: regex-based syntax highlighter
- `tui`: JLine-based terminal frontend (raw mode + alternate screen + ANSI diff)
- `app`: canonical entrypoint and GraalVM native build configuration

## Project Status

This project is currently feature-first: new capabilities sometimes arrive before the dust from recent changes has settled.
The editor is functional, cleanup is ongoing, and a larger refactor is planned once the core feature set stabilizes.

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

Run from an interactive TTY terminal. Non-interactive consoles may fall back to a dumb terminal and fail.

Run with file and flags:

```bash
./gradlew :app:run -PappArgs="file.txt"
./gradlew :app:run -PappArgs="--no-mouse file.txt"
./gradlew :app:run -PappArgs="--no-alt-screen file.txt"
./gradlew :app:run -PappArgs="--no-color file.txt"
./gradlew :app:run -PappArgs="--theme=light file.txt"
./gradlew :app:run -PappArgs="--read file.txt"
./gradlew :app:run -PappArgs="--profile --profile-file=profile-jvm.log file.txt"
./gradlew :app:run -PappArgs="--help"
```

## Profiling (render loop)

Supported profiling flags:

- `--profile` enables frame timing logs (every 30 frames) to `stderr`.
- `--profile-file=<path>` or `--profile-file <path>` writes the same logs to a file and also enables `--profile`.

JVM examples:

```bash
# log to stderr (redirect to file if needed)
./gradlew --console=plain :app:run -PappArgs="--profile file.txt" 2>profile-jvm.log

# log directly to file from app argument
./gradlew --console=plain :app:run -PappArgs="--profile-file=/absolute/path/profile-jvm.log file.txt"
```

Native example:

```bash
./apps/tuink/launcher/build/native/nativeCompile/tuink --profile --profile-file=/absolute/path/profile-native.log file.txt
```

Profile line format:

```text
frame=30 build=3.10ms raster=0.67ms diff=170.09ms search=0.00ms total=174.03ms
```

Metrics:
- `build`: render-plan/UI preparation.
- `raster`: rasterization into `CellBuffer`.
- `diff`: ANSI diff generation between previous/current frame.
- `search`: search match counting for search UI.
- `total`: full frame time.

## Native Build

```bash
./gradlew :app:nativeCompile
./gradlew :app:nativeCompile -Ptarget=musl
./gradlew :app:nativeCompile -Ptarget=all
```

For older x86_64 CPUs, musl builds default to `-march=compatibility`.
You can override it explicitly:

```bash
./gradlew :app:nativeCompile -Ptarget=musl -PnativeMarch=compatibility
./gradlew :app:nativeCompile -Ptarget=musl -PnativeMarch=native
```

Default binary names:
- `tuink`
- `tuink-musl`

## Native Tasks Reference

Level 1 (basic):

```bash
./gradlew :app:nativeCompile
./gradlew :app:nativeCompile -Ptarget=musl
./gradlew :app:nativeDistTarGz                 # default target=glibc
./gradlew :app:nativeDistTarGz -Ptarget=musl
./gradlew :app:nativeDistTarGz -Ptarget=all
```

Level 2 (advanced, PGO):

```bash
./gradlew :app:nativeProfileInstrument -Ptarget=glibc
./gradlew :app:nativeProfileInstrument -Ptarget=musl
./gradlew :app:nativeProfileInstrument -Ptarget=all
./gradlew :app:nativeProfileBuild -Ptarget=glibc -PpgoProfile=/absolute/path/to/profile.iprof
./gradlew :app:nativeProfileBuild -Ptarget=musl -PpgoProfile=/absolute/path/to/profile.iprof
./gradlew :app:nativeProfileBuild -Ptarget=all -PpgoProfile=/absolute/path/to/profile.iprof
```

## Install

Install scripts are copied next to native binaries after native build.

### Install from native build output

Build native binary:

```bash
./gradlew :app:nativeCompile
# or musl
./gradlew :app:nativeCompile -Ptarget=musl
```

Install:

```bash
cd apps/tuink/launcher/build/native/nativeCompile
./install.sh               # auto-detects tuink/tuink-musl
# or explicitly:
./install.sh tuink
./install.sh tuink-musl
```

This installs:
- binary to `/opt/tuink/tuink`
- scripts to `/opt/tuink/install.sh` and `/opt/tuink/uninstall.sh`
- release docs (if present nearby): `README.md`, `LICENSE`, `THIRD_PARTY_NOTICES.md`
- symlink `/usr/local/bin/tuink -> /opt/tuink/tuink`

Uninstall:

```bash
cd apps/tuink/launcher/build/native/nativeCompile
./uninstall.sh
```

### Install from release tar.gz

Build archives:

```bash
./gradlew :app:nativeDistTarGz
./gradlew :app:nativeDistTarGz -Ptarget=musl
./gradlew :app:nativeDistTarGz -Ptarget=all
```

Then:

```bash
tar -xzf apps/tuink/launcher/build/distributions/tuink-<version>-<os>-<arch>.tar.gz
cd tuink-<version>-<os>-<arch>
./install.sh
```

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
