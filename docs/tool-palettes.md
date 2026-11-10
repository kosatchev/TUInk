# Tool palettes

The left dock contains independent **Files** and **Search** palettes. Files is
initially expanded and Search collapsed. Ctrl+E expands Files and focuses the
tree; a distinct Ctrl+Shift+F expands Search and focuses its query. Both can stay
open beside Source or Markdown Preview. Ctrl+B hides or restores the whole dock.
Esc returns focus to the document; in an open palette menu the first Esc closes
the menu.

Click the header title or its arrow once to collapse or expand the palette. A collapsed
palette occupies one header row. Collapsing retains its contents, selection,
scroll position and query. Project search continues collecting bounded results;
its collapsed header shows progress or the result count. Stop in Search is the
explicit cancellation action.

Drag the vertical boundary beside the editor to resize the dock. When both
palette bodies fit, drag their horizontal divider to resize their heights.
Minimum heights keep the controls usable. Drag a header by its title to move a
palette above or below its neighbour after holding the left mouse button without
moving for 500 ms; the insertion line appears when motion starts after that hold.
Files, selected text and palette titles use TUIframe's shared gesture. Any motion
before the deadline cancels the move for this press, even after returning to the
starting cell and waiting. Release and press again to retry; cancelled title motion
never collapses the palette.
Release outside the dock to cancel a move. Hiding the dock, opening a modal or
resizing the terminal cancels a captured gesture.

Ordinary Files and Search text, labels and counters share a fixed
light-gray-on-black body. Headers use the theme's accent, with a separate focused
style, as provided by TUIframe. Search status and help rows use semantic notice
colors. Both palettes have contextual help, enabled by default. The selected
list row uses white on the Cyan/Blue accent while the list itself has focus, or
black on white in Monochrome and without color. Without list focus, it uses black
on light gray (`#BCBCBC`), including focus on a palette header or input field.
Disclosure arrows and labels share the row's colors. Lists hide the terminal
cursor so it cannot invert an individual arrow or filename cell. Gray on gray
is reserved for disabled rows. Selected folders and search groups use the row's
own emphasis; inactive black text stays non-bold so terminals do not brighten it
to gray. Without terminal color, the focused row is also underlined to distinguish
it from inactive selection. Input fields follow the [shared controls rule](controls.md).

Opening a menu or dialog removes palette focus while preserving the ordinary
colors of its contents and the search target to restore on return. The overlay
owns input; it does not make the whole Search palette visually disabled.

The **⋮** button, or a right click on a header, opens its operations menu from
the dots, preferably inside its palette body without covering the header. When
space is insufficient it may extend into the workspace, staying within the
terminal; short menus retain keyboard and wheel access to every operation.
The menu offers:
Collapse/Expand, Maximize/Restore size, Move up/down, Taller/Shorter,
Wider/Narrower and finally Hide hints/Show hints. End selects the hint toggle,
including in shortened menus. Maximizing gives one palette the whole dock; restoring recovers
the preceding order, expansion and relative heights. Ctrl+E or Ctrl+Shift+F can
reveal the other palette from this mode. Hints can be switched independently
for Files and Search; hiding them gives their row back to the tree or results.
On very short palettes, help is temporarily omitted to keep content usable.
Search progress, results summaries and errors remain visible with help hidden.

## Keyboard access

Tab and Shift+Tab move between headers and palette contents. From the tree,
Tab reaches the next header and Shift+Tab its own header. Shift+Tab from the
Search query reaches its header. With a header focused:

- Up/Down selects the other header.
- Enter toggles expansion; Left collapses and Right expands or enters the body.
- Space opens the operations menu; Up/Down selects, Enter activates, Esc closes.
- Alt+Up/Down moves the palette.
- Ctrl+Up/Down makes it shorter/taller; Ctrl+Left/Right makes the dock narrower/wider.

These keys are scoped to headers. The document, file tree and query retain their
ordinary editing and navigation keys when their contents have focus.

## Small terminals and persistence

When the dock's minimum width and the editor's 24-column minimum cannot fit,
the dock is temporarily hidden. When expanded palette bodies cannot fit in the
available height, the active palette receives the body area; other headers stay
available. A header marked **compact** indicates this temporary compression.
Very short allocations show the active header. Growing the terminal restores
the preferred layout without changing saved expansion and size preferences.

Normal application exit saves the layout through the existing guarded settings
writer. Folder roots, search queries and results remain session state. Palette
settings are optional fields of the current settings format:

| Property | Default | Accepted value |
|---|---|---|
| `palettes.width` | `30` | Canonical integer 16–10000 |
| `palettes.filesHeight` | `6` | Relative body height, canonical integer 1–10000 |
| `palettes.searchHeight` | `18` | Relative body height, canonical integer 1–10000 |
| `palettes.filesExpanded` | `true` | `true` or `false` |
| `palettes.searchExpanded` | `false` | `true` or `false` |
| `palettes.searchFirst` | `false` | `true` or `false` |
| `palettes.maximized` | `none` | `none`, `files`, `search`; temporarily overrides saved expansion |
| `palettes.filesHints` | `true` | `true` or `false`; contextual Files help |
| `palettes.searchHints` | `true` | `true` or `false`; contextual Search help |
| `palettes.visible` | `false` | `true` or `false`; explicitly opening a folder reveals the dock |

Missing optional fields use their defaults. Invalid values and unknown keys
follow the ordinary unsupported-settings flow, preserving the original file.
Settings conflicts and temporary-preference sessions retain the existing rules.

`ToolPalette` and `PaletteDock` live in TUIframe's widget module. The former
owns header/menu interaction and typed reorder gestures; the latter calculates
geometry from application-owned preferences. Neither has filesystem or editor
dependencies. TUInk owns Files/Search state, focus, settings and worker lifetimes.
The operations menu uses the containing palette's popup roles; dropdowns invert
their immediate container. See [shared controls](controls.md).
