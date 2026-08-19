# TUInk Compatibility Policy

TUInk supports the current release, the current storage formats, and the current installation layout. Backward compatibility is not guaranteed unless an exception is explicitly approved and documented.

This policy governs application storage and installation contracts. It does not
define compatibility for future published Java toolkit APIs, plugin APIs, or an
out-of-process protocol; those receive independent versions and policies before
public alpha.

## API compatibility

All current toolkit, `editor-contract`, and `editor-session` APIs are internal
and incubating. Repository releases do not yet promise source or binary
compatibility for them.

Before any Java or plugin API becomes public, it must have its own SemVer/API
version policy, automated API baseline, compatibility fixture, and documented
deprecation period. At that point the default rule below against retaining
deprecated APIs does not apply to that published contract during its supported
deprecation window. Storage schema versions and plugin/API versions remain
independent.

## Default rule

Do not add compatibility machinery for obsolete TUInk versions. In particular, avoid:

- migration classes or migration chains;
- multiple readers for historical formats;
- aliases for removed properties, commands, or paths;
- release-specific conditionals;
- deprecated APIs kept only for old callers;
- silent repair or reinterpretation of incompatible data;
- installer or uninstaller logic for layouts that TUInk no longer owns.

Natural compatibility is acceptable when the current implementation already understands older data without extra branches, ambiguity, or maintenance cost.

## Exceptions

An exception requires an explicit maintainer decision. Before implementing one, document:

1. Which released versions or external consumers need it?
2. What user data or workflow would otherwise be lost?
3. Why is safe rejection insufficient?
4. What is the smallest bounded implementation?
5. When and how will the exception be removed?

Security invariants are not legacy support. Validation, path containment, ownership markers, permission checks, and safe failure remain mandatory even when old data is rejected.

## What is not legacy compatibility

Platform and environment support should not be removed merely because it introduces alternatives. Examples include:

- terminal capability and ANSI variants;
- POSIX and non-POSIX behavior;
- atomic-move fallback when the filesystem does not support atomic moves;
- glibc and musl native targets;
- portable CPU builds;
- language aliases and documented CLI forms;
- graceful degradation in limited interactive terminals.

These branches support current environments rather than historical TUInk releases.

## Storage formats

Persisted files must have an explicit format marker, strict validation, complete current-schema serialization, and safe handling of incompatible content. A format change replaces the current contract; it does not imply a migration path.

### Settings

Settings are stored at `~/.config/tuink/tuink.properties` with the current marker:

```properties
format=1
ui.showTabBar=true
ui.wrapLines=false
ui.wrapWords=false
ui.readingMode=false
ui.confirmOnClose=true
ui.mouseMode=true
ui.highlightActiveLineNumber=true
ui.smartHomeEnd=true
ui.autoIndent=true
ui.autoPairs=true
ui.autoList=true
ui.visibleWhitespace=false
ui.syntaxHighlighting=true
ui.rainbowBrackets=true
ui.autoSelectionHighlight=true
ui.gutterDiffMode=...
ui.tabWidth=4
ui.tabInsertMode=...
ui.theme=...
```

The reader accepts only the current schema. Unknown formats, malformed values, missing required fields, and unsupported combinations are incompatible. TUInk must preserve the incompatible file, use documented defaults for the running process, and emit a clear warning. It must not silently rewrite the file as though it had been read successfully.

### Recovery snapshots

Recovery snapshots are stored under `~/.config/tuink/recovery/`. The current schema is:

```properties
format=4
createdAtEpochMillis=...
textGeneration=0123456789abcdef0123456789abcdef
dirty=true
currentFile.present=false
currentFile=
saveDisposition=CREATE_NEW
fileEncoding=UTF-8
bomKind=UTF_8
defaultLineSeparator=CRLF
lineSeparators=CRLF,LF,CRLF
cursor.line=0
cursor.col=0
viewport.topLine=0
viewport.topWrapSegment=0
viewport.leftColumn=0
viewport.leftVisualColumn=0
selection.present=false
selection.line=0
selection.col=0
```

`bomKind` is one of `NONE`, `UTF_8`, `UTF_16_LE`, or `UTF_16_BE` and must be
compatible with `fileEncoding`. `lineSeparators` records every separator in file
order using `LF`, `CRLF`, and `CR`; an empty document has an empty value. For
`n` recovered logical lines it contains exactly `n - 1` values. A final file
separator is represented canonically by a trailing empty logical line.
`defaultLineSeparator` is used for newly inserted lines; loading chooses the
most frequent separator, with the first encountered separator breaking ties.
`saveDisposition` is either `CREATE_NEW` or `REPLACE_EXISTING`; it preserves
whether the current path was observed absent and therefore must not be
overwritten without a later explicit authorization.

`textGeneration` selects `<entry>.<generation>.txt`; the stable
`<entry>.meta.properties` file is the commit point. Text is written to a new
generation before metadata is replaced. Where atomic move is supported,
interruption of the editor process between those writes can therefore leave
only an unreferenced orphan, never a hybrid of two snapshots. The portable
non-atomic fallback and this protocol generally do not claim power-loss
durability without filesystem-specific fsync guarantees.

Explicit deletion first renames live metadata to
`<entry>.delete.properties`, which is a deletion commit marker, and only then
removes generation files. Partial cleanup is retried on startup (and before the
same runtime id is reused), so live metadata never points at text already
removed by the discard operation.

Recovery readers accept only the current `format=4` schema. Earlier snapshots
do not provide both the complete file metadata and generation commit protocol,
so they are reported as incompatible and left unchanged rather than being
upgraded implicitly.

All fields must be validated before a snapshot is used. Incompatible snapshots must remain intact, must not be overwritten by autosave, and may be deleted only through an explicit user action. The recovery directory and files must use restrictive permissions where the platform supports them: `0700` for directories and `0600` for files. Symlinks must not be followed when reading, writing, or deleting recovery data.

## Installation archives

The supported system installation layout is `/opt/tuink` with the launcher at `/usr/local/bin/tuink`. Packaging scripts may replace an existing installation only when its ownership marker identifies the same supported TUInk layout.

Installation must refuse markerless, unknown, or foreign directories. Uninstallation must remove only paths proven to be owned by TUInk and must not infer ownership from a familiar directory name alone.

## Safe rejection

When current code encounters incompatible data or state, it must:

1. detect the incompatibility before mutation;
2. explain what was rejected and where it was found;
3. preserve user data unless deletion was explicitly requested;
4. continue with safe defaults when that is unambiguous, otherwise stop;
5. avoid creating a file that could be mistaken for a successful conversion.

## Tests

Persistence changes must test current-format round trips, missing and unknown format markers, incomplete schemas, non-canonical values, preservation of incompatible data, atomic saves, permissions where supported, non-POSIX behavior, and symlink/path traversal defenses.

Installer and uninstaller changes must test a clean installation, replacement of a marked TUInk installation, refusal of markerless or foreign paths, path containment, and idempotent cleanup of owned files.

Tests for obsolete TUInk formats should exist only when an approved exception requires them.

## Documentation and releases

Documentation must describe the current contract rather than promise unspecified backward compatibility. When a release changes a persisted schema, installation marker, or owned path, its release notes must state that older state is rejected and explain how users can preserve or remove it manually.
