# Shared terminal controls

`libs/tuiframe/widgets` owns interaction, geometry and default presentation for
controls. TUInk supplies labels, values, application actions and theme colors.

| Widget | Contract |
|---|---|
| `TextField` | Inverse background across its entire allocation, including empty padding. Focus uses the theme accent; text selection contrasts with the input. A placeholder is presentation only. |
| `CheckButton` | Owner-controlled boolean value, a persistent check mark. Colors follow the same focus roles as an ordinary button. Activation emits the next boolean value; both states reserve the same width. |
| `DropDown<T>` | An ordinary button with a down arrow when closed; the open list inverts its own container's colors. Typed immutable choices, current value and retained popup interaction. Enter, Space, Down or Alt+Down opens the list. Arrows, Home/End and Page keys navigate; Enter/Space confirms. Esc, Tab and an outside press cancel without changing the value. |

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
