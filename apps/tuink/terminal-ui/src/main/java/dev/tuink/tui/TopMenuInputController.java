package dev.tuink.tui;

import dev.tuink.tui.ui.MenuBar;

final class TopMenuInputController {
    private TopMenuInputController() {
    }

    static HandleResult handleWhenOpen(MenuBar topMenu, KeyEvent key) {
        if (!topMenu.isOpen()) {
            return HandleResult.notConsumed(key);
        }

        KeyEvent.Action mappedAction;
        if (key instanceof KeyEvent.CharInput charInput) {
            mappedAction = topMenu.handleChar(charInput.ch());
        } else {
            KeyEvent.Action action = ((KeyEvent.ActionEvent) key).action();
            mappedAction = topMenu.handleKey(action);
        }

        Integer tabActivation = topMenu.consumeTabActivationRequest();
        KeyEvent nextKey = mappedAction == null ? key : new KeyEvent.ActionEvent(mappedAction);
        return new HandleResult(true, topMenu.isOpen(), tabActivation, nextKey);
    }

    record HandleResult(
        boolean consumed,
        boolean keepMenuOpen,
        Integer tabActivation,
        KeyEvent nextKey
    ) {
        static HandleResult notConsumed(KeyEvent key) {
            return new HandleResult(false, false, null, key);
        }
    }
}
