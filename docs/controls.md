# Shared terminal controls

`libs/tuiframe/widgets` owns interaction, geometry and default presentation for
controls. TUInk supplies labels, values, application actions and theme colors.
Source search, Markdown Preview, project search and project-search settings use
the same controls.

| Widget | Contract |
|---|---|
| `TextField` | Inverse background across its entire allocation, including empty padding. Focus uses the theme accent; text selection contrasts with the input. A placeholder is presentation only. |
| `CheckButton` | Owner-controlled boolean value, a persistent check mark. Colors follow the same focus roles as an ordinary button. Activation emits the next boolean value; both states reserve the same width. |
| `DropDown<T>` | An ordinary button with a down arrow when closed; the open list inverts its own container's colors. Typed immutable choices, current value and retained popup interaction. Enter, Space, Down or Alt+Down opens the list. Arrows, Home/End and Page keys navigate; Enter/Space confirms. Esc, Tab and an outside press cancel without changing the value. |
| `ToolPalette` | Accented headers by default, single-click expansion and captured reorder gestures. The dots menu prefers the owner's body and avoids the header when space allows. A per-palette hint toggle and optional help row share the same component. |
| `PaletteDock` | Placement, minimum sizes, separators and space allocation for palettes. |

`ControlStyles` defines default input, list selection and popup surfaces. `ACCENT`
is the common focused input/list role. Themes can explicitly
override `TEXT_FIELD*`, `BUTTON*` and `POPUP_BODY` / `POPUP_SELECTED` roles.
Buttons, check buttons and closed dropdowns share `BUTTON*` roles. A check mark
indicates a value, while only focus changes a button's colors.
Unfocused inputs are inverse by default. TUInk uses explicit dark-text/gray-background
colors in palettes and dialogs; empty padding has the same surface. Focus changes
the entire field to the theme accent. The exception
is an inline notification container: Find/Replace and Save As fields inherit the
ordinary colors of their respective message row until focused, when the field
inverts the row's colors. Text selection contrasts with that focused surface. A dropdown in
that same row still inverts its container when opened; inherited popup styles
from outer surfaces cannot replace its colors. Palette menus retain their own
popup roles. A checked value, keyboard focus and a highlighted menu row remain
distinguishable without color.

`TEXT_FIELD_PLACEHOLDER`, `TEXT_FIELD_PLACEHOLDER_FOCUSED` and
`TEXT_FIELD_PLACEHOLDER_DISABLED` control secondary, readable placeholder ink.
`ControlStyles.placeholder` retains the input's background and inversion, even
when a placeholder theme role supplies another surface. Focus leaves the example
visible until text is entered; it never becomes the value. Disabled inputs use
muted content and their labels are dimmed by the owner. They are skipped by focus
navigation; no additional “disabled” label is rendered. Placeholder visibility
uses the same empty-value condition in every enabled state.

The framework's default input inversion is a `CellStyle` attribute, encoded as
ANSI reverse video by the terminal presenter. TUInk's explicit neutral input
palette also guarantees its visible background when the parent surface or the
terminal's default colors differ. Semantic inversion remains available to themes
which use terminal-default colors; swapping two unspecified colors would not
provide contrast. The renderer resets
reverse video when writing a subsequent ordinary style.

## Placing and routing a dropdown

A window retains each `DropDown` instance, as it retains a `TextField`. It can
synchronize the selected value with `setValue`; setting the same value preserves
an open popup. A new layout is required after handling state-changing input.

Use `layout(anchor, viewport)` with the viewport of the containing surface, rather
than the toolbar's single row. The popup fits within that viewport and opens
above an anchor near the bottom. `PlacedPopup.paintPopup` is a separate overlay
pass after ordinary content; route input to an open popup before document or
toolbar targets. Outside presses are consumed while dismissing the popup.
`mapOutput` retains popup, cursor and pointer-capture capabilities.

TUInk dismisses local search popups on resize, hidden/blocked search controls and
surface switches. Application-specific search modes and encoding options live in
`ui/SearchControls`; the widgets depend on neither search services nor files.
Palette content, persistence and worker lifetime remain application-owned; see
[Tool palettes](tool-palettes.md).

## Palette headers and operations menus

`ControlStyles.paletteHeader` supplies an accent by default. Applications can
specialize `PALETTE_HEADER` and `PALETTE_HEADER_FOCUSED` through their theme;
normal headers remain distinct from ordinary body text.

`ToolPalette.layout(paletteBounds, viewport)` accepts the full owning palette,
including its body. The placement's interactive bounds still describe only the
header. The menu aligns inward from the dots and prefers the body. If it cannot
fit there, it uses the surrounding viewport below or above the header. A short
menu with keyboard/wheel scrolling takes priority over covering the header;
overlap is unavoidable only when no other viewport row is available.

## Palette hints and list selection

`ToolPalette` exposes **Hide hints / Show hints** as the last owner-controlled
menu action; End selects it even in a shortened menu.
`bodyLayout` reserves a help row only when the minimum content height still fits;
when hints are hidden, the row returns to the content. `BodyLayout.paintHint`
uses the same informational notice style in every palette. Applications supply
contextual help text and persist each palette's visibility preference.

`ListView` and `TreeView` share `ControlStyles.listSelection`: the selected row
uses the accent only while its list has focus. Focus on an input or palette
header, another palette or the document leaves an inverse selected row.

Primary search buttons use `BUTTON_PRIMARY` or `BUTTON_WARNING` for colored
text on the containing body. Their focused variant inverts that button surface;
Search, Stop and Refresh do not keep a filled background while unfocused.

## Moving objects with the mouse

`PointerDragPolicy` defines a shared stationary 500 ms hold for object drag and
drop. The file explorer, palette headers and optional selected-text moves all use
its immutable `Gesture`. Movement before the deadline permanently cancels that
press. Returning to the starting cell or waiting longer cannot rearm it; releasing
and pressing again starts a fresh gesture. Holding alone only prepares the move;
subsequent motion starts dragging. Each terminal cell of movement counts.

Owners schedule `Gesture.nanosUntilWakeup(now)` / `ToolPalette.State.nanosUntilWakeup(now)`
and call their tick method to prepare a stationary hold. The same transition also
works on the first motion event after the deadline, without a preceding timer tick.
Cancellation clears pending and active drags. Ordinary clicks, text selection,
disclosure clicks, scrollbars and resize handles keep their immediate interaction.

Selected-text moves require holding inside the existing range before motion.
A quick drag starts ordinary selection immediately. Source owns a captured
revision/range, renders a separate insertion cursor and cancels stale captures.
The editor engine composes original text ranges and separators without publishing
to the clipboard, committing one undoable command. Dropping inside that range,
outside content, or after cancellation leaves the document unchanged.
