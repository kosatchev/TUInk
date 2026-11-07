# ADR 0006 Markdown preview ownership

- Status: accepted for implementation
- Date: 2026-11-04

Markdown preview derives immutable semantic content from the current document
snapshot. It shares document identity, revision and save/history ownership with
the editor; mode, selection and viewport belong to each frontend view.

`markdown-preview` owns portable Java 17 models and provider ports.
`markdown-commonmark` isolates CommonMark 0.24.0 and its tables, strikethrough
and task-list extensions. Math and Mermaid implementations are independent
Java 17 adapters with no external runtime dependencies. Their expression and
diagram models stay portable; terminal layout belongs to `render` and window
composition, input, scheduling and painting belong to `tui`.

The first completed renderer includes two-dimensional Unicode math and the
documented flowchart/sequence subset of Mermaid. An unsupported construct may
retain its source and a diagnostic; required examples must render. No image
protocol, browser, network service or Python runtime is required.

Workers receive immutable snapshots. Admission checks document/revision and
request generation; queues, source size, model depth and output are bounded.
The source map retains zero-based UTF-16 editor positions. Engine objects,
terminal cells and platform types do not enter the portable API.

Dependency allowlists retain the existing core/session boundaries. The only
new external runtime components are the four explicitly pinned CommonMark
artifacts in its adapter. External Java 17, D8/R8 and native smoke checks cover
the resulting integration. Device lifecycle and ART remain platform gates.

See the [preview guide](../markdown-preview.md),
[paired presentation ownership](0007-paired-markdown-presentations.md) and
[examples](../../apps/tuink/samples/demo_markdown_preview.md).
