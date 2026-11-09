# Shared terminal controls

`libs/tuiframe/widgets` owns interaction, geometry and default presentation for
controls. TUInk supplies labels, values, application actions and theme colors.

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

`TEXT_FIELD_PLACEHOLDER`, `TEXT_FIELD_PLACEHOLDER_FOCUSED` and
`TEXT_FIELD_PLACEHOLDER_DISABLED` control secondary, readable placeholder ink.
`ControlStyles.placeholder` retains the input's background and inversion, even
when a placeholder theme role supplies another surface. Focus leaves the example
visible until text is entered; it never becomes the value. Disabled inputs use
muted content and their labels are dimmed by the owner. They are skipped by focus
navigation; no additional “disabled” label is rendered. Placeholder visibility
uses the same empty-value condition in every enabled state.

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
