# ADR 0004: document file fidelity

- Status: accepted
- Date: 2026-08-24

## Context

A line-only model cannot distinguish `a` from `a\n`, retain a byte-order marker,
or reproduce mixed line separators. A no-op open/save therefore changed valid
files and recovery could not reconstruct them.

## Decision

`TextModel` owns `DocumentFileMetadata` alongside logical lines. It records the
charset, compatible BOM kind, default separator for newly inserted lines, and
the exact separators between logical lines. The representation is canonical:
there are always `lineCount - 1` separators, and a final EOL is represented by
a trailing empty logical line. Loaded and interactively inserted final EOLs
therefore have identical cursor, delete, undo, and save behavior.

Normal save and Save As preserve this metadata. Explicit save with a requested
charset/separator is a canonicalization operation, not the default persistence
path. Save As commits the new path and clean state only after I/O succeeds.
The document also retains `CREATE_NEW` versus `REPLACE_EXISTING` filesystem
intent: opening a missing path cannot overwrite a file that appears before the
first save, and recovery preserves the same constraint across restart.
Undo snapshots and recovery snapshots carry the same file metadata.
The document retains a SHA-256 baseline of the exact bytes last loaded or saved,
along with the resolved target path. Normal save and Save As to the current file
reject changed, removed, retargeted, or unverifiable files before committing.
Recovery retains this disk baseline separately from the edited document text.
Save As to a different path remains the way to preserve both versions.
Save As captures immutable document/history preparation on the owner, then
inspects the address on a worker. The inspection pins the physical target,
canonical parent identity, existing-file identity/attributes and a streaming
SHA-256 version. Workspace/user approval binds to that inspection. The worker
checks the submitted spelling before staging, then writes only through the
pinned canonical parent and rechecks the target leaf/version before commit.
It never selects another destination after approval. Retargeting before writing
or changing the target leaf during staging fails without marking the document
saved; changing the original parent alias during encoding cannot redirect the
already canonicalized destination.
Malformed BOM-declared input and text not representable in the retained charset
fail explicitly; encoding never substitutes replacement characters silently.
BOM-less UTF-16 is intentionally not auto-detected: UTF-16 input must declare
its byte order with a BOM, avoiding heuristic corruption of arbitrary byte data.
Explicit UTF-16LE/BE output therefore always writes the matching BOM.
UTF-32 signatures are checked against the complete stream length and scalar
values. A UTF-16LE file beginning with NUL remains readable when its bytes cannot
form UTF-32. When both interpretations are valid, opening requires an explicit
UTF-16LE choice in the picker or `--open-as-utf16le` for that command-line file.
The choice never changes detection for other requests. UTF-32 itself remains
unsupported and must be converted before opening.

Unrepresentable edits offer an explicit Save as UTF-8 flow, with Cancel selected
initially. It writes UTF-8 without BOM and retains each existing line separator.
Successful conversion adds one format-only undo step before the savepoint;
undo restores the old charset/BOM and marks the document dirty, while retaining
earlier text history. Failed writes and cancellation change neither format nor
history. Recovery observes format and file/savepoint changes as well as text.

Documents and preferences share a fail-fast per-user save guard in
`~/.local/state/tuink/save-locks-v1`, independent of configuration profiles.
The key represents the canonical directory entry rather than its replaceable
inode. Baseline, destination and current attributes are rechecked while holding
the guard through publication. Symlink aliases share a guard; distinct hard-link
entries remain distinct because atomic replacement already separates them.
Persistent lock files are never unlinked during normal operation. This serializes
cooperating TUInk writers on one host, not foreign applications or users.

Existing-file replacement stages POSIX permissions and ownership, user-defined
extended attributes, and ACLs exposed by the filesystem provider before commit.
Failure to read or apply supported attributes leaves the original file intact.
Providers exposing `unix:mode` also preserve `setuid`, `setgid`, and `sticky`
after content and ownership changes. The staged mode is verified; restoring
special bits must retain the access ACL's exact mask and named entries.
On the default Linux filesystem provider, libc's extended-attribute functions
preserve `system.posix_acl_access`, which Java's ACL view does not expose.
The bundled `editor-storage-jvm` provider uses Java 25's Foreign Function and
Memory API; the shared engine accesses a Java 17 SPI without native API types.
Missing, broken or ambiguous providers fail the save instead of claiming that
ACLs are unsupported. Native builds substitute the platform implementation
with libc calls linked at build time. No external ACL utility or `libacl` is required.
The full ACL replaces any inherited staged ACL; when the source has no extended
access ACL, the staged ACL is removed. Its entries, mask and the restored Unix
mode must match the source before replacement. Failed calls or verification
abort the save while retaining the original.
This does not promise preservation of security labels or other attributes not
exposed by those Java views. Other hard links keep their original inode and
contents after atomic replacement.

## Consequences

- No-edit open/save preserves BOM, final EOL, and mixed separators.
- Editing a newline uses the document default while untouched separators remain
  exact.
- Recovery format changes when this information becomes required; incompatible
  old snapshots are rejected without overwrite according to storage policy.
