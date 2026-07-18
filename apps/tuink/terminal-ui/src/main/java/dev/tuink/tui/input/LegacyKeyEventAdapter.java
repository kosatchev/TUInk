package dev.tuink.tui.input;

import java.util.Optional;
import java.util.Set;

/**
 * Temporary bridge from physical input to TUInk's legacy action model.
 *
 * @deprecated Remove after keyboard, pointer and paste routing use keymaps.
 */
@Deprecated
public final class LegacyKeyEventAdapter {
    private LegacyKeyEventAdapter() {
    }

    public static Optional<KeyEvent> toLegacy(TuiInputEvent event) {
        if (event == null) {
            throw new IllegalArgumentException("event must not be null");
        }
        if (event instanceof TextInput text) {
            if (text.text().codePointCount(0, text.text().length()) != 1) {
                return Optional.empty();
            }
            return Optional.of(new KeyEvent.CharInput(text.text().codePointAt(0)));
        }
        if (event instanceof KeyStroke stroke) {
            return Optional.of(new KeyEvent.ActionEvent(toLegacyAction(stroke)));
        }
        if (event instanceof PointerEvent pointer) {
            return toLegacyPointer(pointer);
        }
        return Optional.empty();
    }

    private static KeyEvent.Action toLegacyAction(KeyStroke stroke) {
        Set<KeyStroke.Modifier> modifiers = stroke.modifiers();
        boolean shift = modifiers.contains(KeyStroke.Modifier.SHIFT);
        boolean alt = modifiers.contains(KeyStroke.Modifier.ALT);
        boolean ctrl = modifiers.contains(KeyStroke.Modifier.CTRL);
        boolean superKey = modifiers.contains(KeyStroke.Modifier.SUPER);

        if (ctrl && shift && stroke.key() == KeyStroke.Key.S) {
            return KeyEvent.Action.CTRL_SHIFT_S;
        }
        if (ctrl && stroke.key() == KeyStroke.Key.SLASH) {
            return KeyEvent.Action.TOGGLE_LINE_COMMENT;
        }
        if (ctrl && stroke.key() == KeyStroke.Key.PAGE_UP) {
            return KeyEvent.Action.TAB_PREVIOUS;
        }
        if (ctrl && stroke.key() == KeyStroke.Key.PAGE_DOWN) {
            return KeyEvent.Action.TAB_NEXT;
        }
        if ((ctrl || superKey) && stroke.key() == KeyStroke.Key.TAB) {
            return KeyEvent.Action.TAB_NEXT;
        }
        if (alt) {
            return switch (stroke.key()) {
                case B -> KeyEvent.Action.WORD_LEFT;
                case F -> KeyEvent.Action.WORD_RIGHT;
                case I -> KeyEvent.Action.F10;
                case E -> KeyEvent.Action.CTRL_E;
                case T -> KeyEvent.Action.CTRL_T;
                case V -> KeyEvent.Action.OPEN_VIEW_MENU;
                case H -> KeyEvent.Action.OPEN_HELP_MENU;
                case S -> KeyEvent.Action.F2;
                default -> KeyEvent.Action.UNKNOWN;
            };
        }
        if (ctrl) {
            KeyEvent.Action controlAction = switch (stroke.key()) {
                case A -> KeyEvent.Action.CTRL_A;
                case C -> KeyEvent.Action.CTRL_C;
                case E -> KeyEvent.Action.CTRL_E;
                case F -> KeyEvent.Action.CTRL_F;
                case G -> KeyEvent.Action.CTRL_G;
                case H -> KeyEvent.Action.CTRL_H;
                case K -> KeyEvent.Action.CTRL_K;
                case N -> KeyEvent.Action.CTRL_N;
                case O -> KeyEvent.Action.CTRL_O;
                case Q -> KeyEvent.Action.CTRL_Q;
                case R -> KeyEvent.Action.CTRL_R;
                case S -> KeyEvent.Action.CTRL_S;
                case T -> KeyEvent.Action.CTRL_T;
                case V -> KeyEvent.Action.CTRL_V;
                case W -> KeyEvent.Action.CTRL_W;
                case X -> KeyEvent.Action.CTRL_X;
                case Y -> KeyEvent.Action.CTRL_Y;
                case Z -> KeyEvent.Action.CTRL_Z;
                case ARROW_LEFT -> KeyEvent.Action.WORD_LEFT;
                case ARROW_RIGHT -> KeyEvent.Action.WORD_RIGHT;
                default -> null;
            };
            if (controlAction != null) {
                return controlAction;
            }
        }
        if (stroke.key() == KeyStroke.Key.F4 && ctrl && shift) {
            return KeyEvent.Action.CTRL_SHIFT_S;
        }
        return switch (stroke.key()) {
            case ESCAPE -> KeyEvent.Action.ESCAPE;
            case TAB -> KeyEvent.Action.TAB;
            case ENTER -> KeyEvent.Action.ENTER;
            case BACKSPACE -> KeyEvent.Action.BACKSPACE;
            case DELETE -> KeyEvent.Action.DELETE;
            case ARROW_UP -> shift ? KeyEvent.Action.SHIFT_ARROW_UP : KeyEvent.Action.ARROW_UP;
            case ARROW_DOWN -> shift ? KeyEvent.Action.SHIFT_ARROW_DOWN : KeyEvent.Action.ARROW_DOWN;
            case ARROW_LEFT -> shift ? KeyEvent.Action.SHIFT_ARROW_LEFT : KeyEvent.Action.ARROW_LEFT;
            case ARROW_RIGHT -> shift ? KeyEvent.Action.SHIFT_ARROW_RIGHT : KeyEvent.Action.ARROW_RIGHT;
            case HOME -> KeyEvent.Action.HOME;
            case END -> KeyEvent.Action.END;
            case PAGE_UP -> KeyEvent.Action.PAGE_UP;
            case PAGE_DOWN -> KeyEvent.Action.PAGE_DOWN;
            case F1 -> KeyEvent.Action.F10;
            case F2 -> KeyEvent.Action.CTRL_E;
            case F3 -> KeyEvent.Action.OPEN_VIEW_MENU;
            case F4 -> KeyEvent.Action.CTRL_T;
            case F5 -> KeyEvent.Action.F2;
            case F6 -> KeyEvent.Action.OPEN_HELP_MENU;
            default -> KeyEvent.Action.UNKNOWN;
        };
    }

    private static Optional<KeyEvent> toLegacyPointer(PointerEvent pointer) {
        KeyEvent.MouseKind kind = switch (pointer.kind()) {
            case PRESS -> KeyEvent.MouseKind.PRESS;
            case RELEASE -> KeyEvent.MouseKind.RELEASE;
            case MOVE, DRAG -> KeyEvent.MouseKind.DRAG;
            case SCROLL_UP -> KeyEvent.MouseKind.SCROLL_UP;
            case SCROLL_DOWN -> KeyEvent.MouseKind.SCROLL_DOWN;
            case SCROLL_LEFT, SCROLL_RIGHT, UNKNOWN -> null;
        };
        if (kind == null) {
            return Optional.empty();
        }
        KeyEvent.MouseButton button = switch (pointer.button()) {
            case LEFT -> KeyEvent.MouseButton.LEFT;
            case MIDDLE -> KeyEvent.MouseButton.MIDDLE;
            case RIGHT -> KeyEvent.MouseButton.RIGHT;
            case NONE -> KeyEvent.MouseButton.NONE;
            case OTHER -> null;
        };
        if (button == null) {
            return Optional.empty();
        }
        return Optional.of(new KeyEvent.MouseEvent(pointer.row(), pointer.column(), kind, button));
    }
}
