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
Malformed BOM-declared input and text not representable in the retained charset
fail explicitly; encoding never substitutes replacement characters silently.
BOM-less UTF-16 is intentionally not auto-detected: UTF-16 input must declare
its byte order with a BOM, avoiding heuristic corruption of arbitrary byte data.
Explicit UTF-16LE/BE output therefore always writes the matching BOM.

## Consequences

- No-edit open/save preserves BOM, final EOL, and mixed separators.
- Editing a newline uses the document default while untouched separators remain
  exact.
- Recovery format changes when this information becomes required; incompatible
  old snapshots are rejected without overwrite according to storage policy.
