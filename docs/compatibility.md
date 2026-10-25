# TUInk Compatibility Policy

TUInk supports the current release and installation layout. Storage uses the
current write formats, with one explicit recovery-reading exception for formats
5 through 8 described below. Other obsolete contracts are not supported.

This policy governs application storage and installation contracts. It does not
define compatibility for future published Java toolkit APIs, plugin APIs, or an
out-of-process protocol; those receive independent versions and policies before
public alpha.

## API compatibility

All current toolkit, `editor-contract`, and `editor-session` APIs are internal
and incubating. Repository releases do not yet promise source or binary
compatibility for them.

Before any Java or plugin API becomes public, it must have its own version
policy and automated contract checks. Storage schema versions and plugin/API
versions remain independent.

## Default rule

Outside the documented recovery-reading exception, do not add compatibility
machinery for obsolete TUInk versions. In particular, avoid:

- migration classes or migration chains;
- multiple readers for historical formats;
- aliases for removed properties, commands, or paths;
- release-specific conditionals;
- deprecated APIs kept only for old callers;
- silent repair or reinterpretation of incompatible data;
- installer or uninstaller logic for layouts that TUInk no longer owns.

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

Terminal key sequences may arrive in separate reads. CSI, SS3 and recognized
terminal-key prefixes retain their decoding state across idle polls until they
complete or are cancelled. A bare Esc uses an ambiguity window of at most 500 ms;
unambiguous complete keys and Alt combinations do not wait for that window.
A complete terminfo key that is also a prefix of a longer key uses the same
window to select the longest match.

## Document opening

TUInk loads a document into memory and accepts files up to 64 MiB. A conservative
budget based on remaining JVM heap can reduce that limit; the decoded line count
is checked before allocating per-line document state. This is an admission limit,
not a guarantee that every operation on an accepted file will fit any heap.
The stream is bounded independently of its initial size, so a file growing during
the read cannot bypass the byte limit. A rejected load reports an I/O error and
preserves the existing document, file metadata, baseline, revision and undo history.

Ordinary command-line file and profile paths containing `U+FFFD` are rejected
before startup: native argument decoding may already have substituted that
character for undecodable filename bytes. This includes literal `U+FFFD` names.
The file picker and `--file-uri=<uri>` provide exact document identity instead.
The explicit URI must be an absolute ASCII `file:` URI with percent-encoded
filename bytes; its path and authority must round-trip through the native
filesystem without changing their bytes. Ordinary positional paths, including
those after `--`, retain literal path syntax.

File labels use a shared presentation policy. Native byte sequences and Unicode
characters which terminal sanitization would change receive an escaped `file:`
label, including in the picker, tabs and confirmations. A basename label such
as `file:a%FF.txt` is a display identifier, not the absolute URI accepted by
`--file-uri` or Save As. Literal names starting with `file:` are escaped as well,
so the label namespace remains distinct from ordinary filenames. Encoded
basenames must fit completely in destructive confirmations; otherwise the prompt
requires a wider window before accepting the action.

## Document saves

Saving to a new name never overwrites a file created concurrently. Where hard
links are supported, TUInk atomically publishes the complete staged bytes and
permissions. Otherwise it exclusively creates the destination with `CREATE_NEW`
and writes through that open handle. This portable path can leave a partial new
file if writing or closing fails; the error identifies that path, and document
metadata and the saved-file baseline remain unchanged. TUInk does not delete the
destination after such a failure because another writer may have replaced it.
Existing-file replacement retains its atomic-move behavior and baseline checks.

The saved-file baseline includes the original byte count. A changed count rejects
the save before reading file contents; the digest check reads at most that count
plus one byte, so a file growing during verification cannot extend the read.
Save As uses this loaded identity when classifying existing targets. A retargeted
opening symlink does not make its new physical target an owned document: selecting
that target explicitly requires overwrite confirmation. Exact owned names still
require the baseline check when deleted or retargeted, including a Save As request
that would otherwise use `CREATE_NEW`.

For an owned destination, baseline verification reads the pinned physical target
that will receive the staged file. Checking the requested alias separately
confirms that it still resolves to that target. Repeatedly resolving a changing
alias for both checks cannot transfer ownership to another physical filename.
Changes by a writer that ignores TUInk's locks after the final verification
remain outside this protection.

Before replacing an existing file, TUInk copies its POSIX permissions and
owner/group, user-defined extended attributes, and ACLs available through the
Java filesystem provider to the staged inode. An error reading or applying
these attributes aborts the save before replacement and leaves the document's
saved-file baseline unchanged. Attributes unavailable through these views,
including platform-specific security labels, are outside this guarantee.
On providers exposing `unix:mode`, this includes all rwx permissions and the
`setuid`, `setgid`, and `sticky` bits. They are restored after writing contents
and setting ownership, including on edited files, and verified before commit.
If the filesystem refuses or silently clears a required bit, the original
file and save baseline remain unchanged.
On Linux's default filesystem provider, POSIX access ACLs are read and written
through libc's extended-attribute functions using `system.posix_acl_access`.
The complete ACL, including named entries and the exact mask, replaces any ACL
inherited by the staged file. If the original has no extended access ACL, any
inherited staged ACL is removed. Both the ACL and Unix mode are verified before
commit. Failed calls or a mismatch abort saving and retain the original; no
`acl` package or separate `libacl` library is required. Other providers without
an ACL view do not promise ACL preservation. Atomic replacement deliberately breaks
hard links; other names continue to refer to the old inode.

## Interactive search and bulk comments

Interactive wildcard counting, navigation and replacement each have a work limit
for the chosen search scope. A query exceeding it reports `Search is too complex`
and keeps the entered query available for editing. Incomplete counts are never
shown as zero matches. Shorten the wildcard query or use literal search to
continue. Replace All completes its preparation before changing the document,
including when the work limit is reached while building the result.
Optional tab markers use a separate budget. If their scan exceeds it, the failure
is cached until the query, options or documents change; the current tab's count
and highlights remain available when All tabs is disabled.
An identical single replacement resolves its following match before publishing
the new selection. Exceeding the search budget leaves the previous selection,
cursor, viewport and undo grouping intact.

Find, Replace and Save As fields prepare edits and terminal metrics before
publishing their value. Admission covers normalization, text copies and layout
arrays while the previous field remains reachable. A refused edit retains text,
caret, selection and viewport and produces visible input-rejection feedback.

Line-comment toggling prepares the changed lines, line-ending metadata and undo
entry before publishing the edit. If the available-memory budget cannot admit
the operation, the document and history stay unchanged; select fewer lines.

Logical lines use immutable balanced text blocks. Local edits and snapshots share
unchanged blocks; compatibility APIs requesting a complete String still materialize
one. Rendering and syntax context check their range/work limits before doing so.
Grapheme and terminal indices reuse unchanged ranges after a local edit and do not
retain prior text versions. Initial indexing is linear. Regional-indicator parity
and other boundary dependencies may propagate beyond the edited block. General
word/tab wrapping may also reflow the whole line; fixed-width runs use virtual row
starts. These changes do not promise a bounded latency for every Unicode input.
Typing assists limit lexical prefixes and local indentation heuristics. A Markdown
line beyond 1,048,576 UTF-16 units makes its following block context unknown until
that prefix changes; uncertain list and fence assists are suppressed.

## Storage formats

Persisted files must have an explicit format marker, strict validation, complete current-schema serialization, and safe handling of incompatible content. A format change replaces the current contract; it does not imply a migration path.

### Settings

Settings are stored at `$XDG_CONFIG_HOME/tuink/tuink.properties`. Unset, empty,
relative, or invalid `XDG_CONFIG_HOME` values use `~/.config` as the base.
An absolute value is used as supplied, including spaces and symlink/parent
components. The selected profile is used exclusively; no default-profile reader
or automatic migration is introduced. An otherwise usable absolute
`XDG_CONFIG_HOME` containing `U+FFFD` is rejected before opening the editor:
Java's native environment decoding may have replaced Unix filename bytes, so
using that string could select a different directory. The same check applies to
`user.home` when the default profile is selected, including malformed Unicode
surrogates. A valid explicit `XDG_CONFIG_HOME` does not depend on `user.home`.
Relative or invalid XDG values are unused and keep the ordinary home fallback.
Literal `U+FFFD` directory names are conservatively rejected as indistinguishable
from decoding loss. The diagnostic asks for an unambiguous absolute
`XDG_CONFIG_HOME`; no settings or recovery files are read or written in another
profile. The current marker is:

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

The complete settings file, including comments, is limited to 64 KiB. Larger
files are rejected before parsing and left unchanged; the session uses defaults
and reports a warning. Reads also enforce this limit if the file grows after
its size is checked. Conflict checks hash the current file with a bounded
streaming read, so an external writer growing it past the limit produces a
settings conflict without loading the enlarged file into memory.

Each settings save stages a complete snapshot in its own unique temporary file
beside the resolved settings target, then replaces that file. A final symbolic
link is preserved and its existing regular-file target is updated. Dangling
links and non-regular targets are rejected before opening a stream. Concurrent editor
instances never write, move, or delete each other's temporary files.

Automatic preference saving retains the physical target and a fingerprint of
the same bytes used to load the settings. Before staging and again before
replacement, it checks that the target and bytes still match. Detected changes,
including comments or edits saved from TUInk itself, preserve the current file
and produce a warning after exit that the session's menu preferences were not
saved. Deletion, a redirected symlink, or a file appearing where none was loaded
also causes a conflict. A new settings file is published without overwriting
an existing name. An incompatible initial file is never automatically rewritten.
Settings from different instances are not merged. Cooperating TUInk instances
hold an exclusive filesystem lock across the final baseline check and publication.
Documents and settings share persistent locks in
`${user.home}/.local/state/tuink/save-locks-v1`, independently of configuration
profiles. The key identifies the canonical directory entry; symbolic aliases
use the same lock. Unix providers also lock the entry identified by its parent
device/inode and exact filename bytes, coordinating bind-mounted aliases of the
same entry. Different hardlink filenames remain independent destinations.
The original URI lock is retained for compatibility with older versions at the
same canonical path; bind-alias protection requires updated writers on both paths.
A busy, unsafe, or unsupported lock leaves the target intact
and causes a settings conflict or an unsaved document. Lock files remain after
unlocking so a later writer cannot acquire a different inode while another
writer still owns it. No new lock files are created beside edited documents.
Other applications do not participate in this protocol and can still race a save
after its final check.

### Recovery snapshots

Recovery snapshots are stored under the same configuration base in
`tuink/recovery/` (`~/.config/tuink/recovery/` by default). The current schema is:

```properties
format=8
pathPlatform=unix
createdAtEpochMillis=...
textGeneration=0123456789abcdef0123456789abcdef
dirty=true
currentFile.present=false
currentFile=
saveDisposition=CREATE_NEW
fileEncoding=UTF-8
bomKind=UTF_8
defaultLineSeparator=LF
lineSeparators=packed2:3:AA==
fileBaseline.present=false
fileBaseline.target=
fileBaseline.sha256=
fileBaseline.byteSize=
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
compatible with `fileEncoding`. Formats 7 and 8 store `lineSeparators` as
`packed2:<count>:<Base64>`, using two bits per separator (`LF`, `CRLF`, or `CR`).
Codes are 0, 1 and 2 respectively, packed from the least significant pair of
each byte; code 3 is reserved, and unused high bits must be zero.
The example contains three LF separators. Count, padding, reserved codes and
canonical Base64 are validated before allocating a per-line list. Formats 5 and 6 use
the original comma-separated `LF`, `CRLF`, and `CR` values. For
`n` recovered logical lines either encoding contains exactly `n - 1` values. A final file
separator is represented canonically by a trailing empty logical line.
`defaultLineSeparator` is used for newly inserted lines; loading chooses the
most frequent separator, with the first encountered separator breaking ties.
`saveDisposition` is either `CREATE_NEW` or `REPLACE_EXISTING`; it preserves
whether the current path was observed absent and therefore must not be
overwritten without a later explicit authorization.

`pathPlatform` is `unix` or `windows` and must match the native filesystem of
the running editor. `currentFile` and `fileBaseline.target` store canonical ASCII
`file:` URIs produced from native, absolute normalized paths, including percent
escapes for spaces, Unicode and raw Unix filename bytes. They are decoded as
URIs, never through display strings. URI schemes other than `file:`, query or
fragment components, noncanonical encodings and paths that do not round-trip
losslessly on this platform are rejected. Absent paths use an empty field.
Non-root document URIs omit a trailing directory slash, so their decoding does
not depend on an external process replacing the target with a directory.
For example, `file:///home/user/legacy-%FF.txt` retains the byte `FF`; it must
not become a filename containing the Unicode replacement character.

`fileBaseline` records the last successfully loaded or saved file's physical,
absolute normalized target path, original byte count and SHA-256 digest. It is
preserved across recovery so saving a restored document can detect changes made
by another process since that load or save. The digest is exactly 64 lowercase
hexadecimal characters. `fileBaseline.byteSize` is a nonnegative integer, or `-1`
when the original size is unknown. When `fileBaseline.present=false`, target,
digest and byte size must all be empty. A baseline requires `currentFile.present=true` and
`saveDisposition=REPLACE_EXISTING`. An existing-path snapshot without a known
baseline can be restored, but ordinary save refuses to overwrite its file; use
Save As with a different name or reopen the original file.

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

Recovery writers use `format=8`; readers accept the exact schemas for formats
5, 6, 7 and 8. This bounded exception protects unfinished work from format-5
writers from `v0.13` through `v0.15`, as well as formats 6 and 7.
It does not authorize general historical readers or migration chains. Format 8 also
retains `fileBaseline.byteSize` so an externally grown file can be rejected before
reading its content during Save. Discovery and Restore leave compatible older
metadata and text unchanged; a later ordinary autosave writes format 8. Older baselines have an
unknown byte count (`-1`); their conflict checks read at most the current 64 MiB
load limit and refuse larger files with the usual Save As/reopen guidance.

Format 5 has neither `pathPlatform` nor `fileBaseline.byteSize`. Its `currentFile`
and `fileBaseline.target` are absolute normalized native display strings,
serialized with Java properties escaping. They are read literally, including
names containing `file:`, percent escapes, spaces or Unicode; they are not URIs.
Malformed paths, relative paths and paths that cannot round-trip on the current
native filesystem are rejected with guidance to restore using the old binary on
the original platform and use Save As. Format 6 and newer retain their strict
platform-marker and canonical file-URI checks.

The format-5 writer could replace undecodable Unix filename bytes with U+FFFD
in either path field. Such a path cannot establish a safe save destination,
even when another file with the displayed name has the same bytes. Those
snapshots restore their text, encoding and line endings as dirty unnamed drafts,
with `CREATE_NEW` disposition and no file baseline. Use Save As to choose a new
filename. This conservative rule also applies when U+FFFD was a literal filename
character. Neither the original file nor a file matching its display spelling
is written during recovery. An ordinary autosave records the unnamed draft in
format 8.

Earlier schemas, missing or unknown markers and incomplete metadata are rejected
before restoration. There is no automatic
migration for incompatible schemas; rejected metadata and text remain unchanged
and cannot be replaced by autosave.

Startup discovers and claims snapshot descriptors without loading their text.
Text is decoded only after Restore is selected. Writer and reader share hard
limits of 64 Mi UTF-16 code units, 192 MiB of UTF-8 text, and 8 MiB of encoded
metadata. A rejected write retains the previous committed snapshot.
Formats 5 and 6 metadata have a separate 24 MiB read limit, subject to available
memory, to recover snapshots produced by old writers beyond their 8 MiB limit.
Compact separator descriptors are validated without expanding their per-line lists
during discovery. Restore inspects UTF-8 incrementally before allocating the
document, then checks a conservative remaining-heap budget for decoded text
and per-line objects; oversized or malformed text produces a restore error and remains available for Later or
explicit Delete. A partial restore keeps only failed snapshots in the prompt,
so retrying cannot duplicate tabs that were already restored. Restored drafts
are added alongside documents opened explicitly for this session; only a pristine
unnamed startup placeholder is replaced.

Each entry has a private `<entry>.lock` sidecar. A running editor holds an
exclusive filesystem lock while it owns that entry, including while a startup
restore/discard prompt is awaiting input. Other instances skip owned entries
and cannot overwrite or delete them, including pending deletion cleanup.
Closing the store or terminating its process releases ownership without
deleting the snapshot. Stores share a stable `.namespace.lock` while they can
open entry locks. After closing every entry channel, the last cooperating store
can take the namespace lock exclusively and reclaim unused entry sidecars.
This prevents a waiter from locking an unlinked inode. The namespace lock itself
is retained; a tab that never had a snapshot requires no entry sidecar.
Run versions using this lock protocol together; an older binary without the
namespace guard must not open entries concurrently in the same profile.
Lock files contain no document data and do not change the snapshot schema.
A filesystem that cannot provide locking fails safely with a recovery warning.

On its first successful initialization, a store resolves its recovery directory
to a physical path and uses that path for both snapshots and locks for its
lifetime. Retargeting a parent symlink affects newly initialized stores; active
stores continue using their original directory.

Only an application exit authorized by the editor's quit flow clears its
remaining runtime snapshots. End of input, an external interrupt, or an
application failure preserves recovery and attempts a final save of dirty
tabs without the usual autosave delay. Abrupt process termination can retain
only the last successfully committed snapshot.

Automatic snapshot writes are spaced by at least two seconds after each
attempt, including a failed attempt. A failed update retains the previously
committed snapshot. The final save on end of input, interruption, or application
failure bypasses this retry delay.
The owner thread captures one immutable snapshot, and a single background worker
performs its filesystem I/O. At most one operation is in flight; pending edits
are captured from the current state on a later turn rather than queued as extra
snapshots. Completion and retry state are applied on the owner thread. Shutdown
drains the outstanding operation before final synchronous saves or cleanup,
so an old write cannot recreate a discarded snapshot.

Paste and single Replace admission check replacement text, changed-line objects,
whole-document reference arrays, metadata, and compact undo deltas. Text,
metadata, view and history are prepared before publishing the replacement;
a rejected operation leaves text, selection, viewport, revision, and undo/redo intact.
Display payloads are limited to 256 UTF-16 units per grapheme. Oversized clusters
use a one- or two-cell replacement glyph to preserve their original geometry;
the document, clipboard, and saved bytes retain the complete text.
Standalone zero-width formatting characters, including bidi marks, embeddings
and isolates, are displayed as `�` rather than emitted literally. Their source
code points remain unchanged in the document and saved file. Joiners inside
ordinary text and emoji graphemes retain their shaping behavior.

All fields must be validated before a snapshot is used. Incompatible snapshots must remain intact, must not be overwritten by autosave, and may be deleted only through an explicit user action. The recovery directory and files must use restrictive permissions where the platform supports them: `0700` for directories and `0600` for files. The recovery directory itself and snapshot/lock files must not be symlinks.

## Installation archives

The supported system installation layout is `/opt/tuink` with the launcher at `/usr/local/bin/tuink`. Packaging scripts may replace an existing installation only when its ownership marker identifies the same supported TUInk layout.

Installation must refuse markerless, unknown, or foreign directories. Uninstallation must remove only paths proven to be owned by TUInk and must not infer ownership from a familiar directory name alone.

The current archive layout also installs third-party notices under
`/opt/tuink/licenses/`. `/opt/tuink/.tuink-license-files` records ownership of
those files: its first line is exactly `# tuink-license-files-v1`, followed by
a nonempty list of paths relative to `licenses/`. Existing license data requires
a regular manifest with this current format. Unknown or malformed manifests,
absolute paths, `.`/`..` components, and symlinked files or parent directories
are rejected before installation or removal changes.

Replacement must not overwrite license destinations absent from the previous
ownership manifest. Uninstallation removes only the recorded license files and
their empty parent directories. Unlisted files and foreign empty directories
remain intact; if they remain, the license directory and installation container
are preserved after the managed executable, launcher, and ownership markers
are removed. No historical manifest readers or directory-name ownership
fallbacks are supported.

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

Tests cover the current schema and rejection of missing, unknown, or malformed markers.

## Documentation and releases

Documentation and release notes describe the current supported contract,
including the recovery-reading exception and its Save As guidance. Other
obsolete versions do not receive migration instructions.
