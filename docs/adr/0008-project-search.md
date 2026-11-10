# ADR 0008: project search and sidebar ownership

- Status: accepted
- Date: 2026-11-10

## Context

Folder search combines live document text with unopened files. Running it through
the document engine would create unnecessary editor sessions, retain undo/history
state and couple the engine to directory traversal. Separate matching engines
would also diverge on Unicode, whole-word and wildcard ranges.

## Decision

`editor-search-text` owns the existing matcher, operation budgets and cancellation.
Its Java packages and matching semantics remain stable; `core` exports the shared
library through its Java 17 API variant. It depends only on `editor-contract`.
Matcher-specific tests belong in that library, while document navigation tests
remain with the engine.

`project-search` is another Java 17 library. It receives immutable requests and
source ports, matches logical text lines, bounds retained results and emits typed
progress, issue, match and completion events. It creates neither threads nor UI
objects and knows no filesystem paths. It does not depend on `editor-session`.

`project-search-jvm` implements the source inventory with bounded traversal,
Git-style ignore rules, regular-file streaming reads and physical aliases of
open buffers. The document codec owns the shared automatic-encoding decision;
the streaming adapter validates complete byte streams and checks their versions.
JGit remains a platform implementation detail. Independent diff and search
adapters consume one shared unsigned JGit artifact from Gradle build logic.

The TUInk window owns `ProjectContext`, `SidebarState`, `ProjectSearchPanel` and
`ProjectSearchController`. ProjectContext is the single working-root authority.
SidebarState hosts independent Files and Search tool palettes in one dock.
TUIframe's ToolPalette and PaletteDock provide reusable header interaction and
geometry; TUInk owns saved layout preferences and the contents' lifetimes.
The application captures document snapshots on their owner and supplies them to
the detached search; editor-session retains document and presentation ownership.
TUIframe supplies ordinary controls and contains no project-search policy.

The host retains at most one executing search and one debounced pending intent.
The worker uses a bounded queue with explicit cancellable backpressure. UI turns
drain a bounded number of events. Every reply carries the search generation;
query/root changes and shutdown revoke old generations before publication.
Open-buffer revisions identify stale results, and navigation revalidates the
exact source before selecting a range through the document command boundary.

## Consequences

- Local and project searches share matching semantics and UTF-16 ranges.
- Portable search can be exercised without a terminal, filesystem or editor host.
- Search does not occupy the document save/open executor.
- Folder navigation and search retain independent state when hidden or switched.
- No persistent index or filesystem watcher is introduced; result completeness,
  cancellation and resource limits are explicit.
