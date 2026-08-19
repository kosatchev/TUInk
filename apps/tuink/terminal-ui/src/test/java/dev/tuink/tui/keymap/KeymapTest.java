package dev.tuink.tui.keymap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tuink.editor.command.CommandInvocation;
import java.util.Map;
import org.junit.jupiter.api.Test;

class KeymapTest {
    @Test
    void resolvesPhysicalKeysToSemanticInvocations() {
        Keymap<PhysicalKey> keymap = new Keymap<>();
        CommandInvocation save = CommandInvocation.of("tuink.editor.save");
        keymap.bind(PhysicalKey.SAVE, save);

        assertEquals(save, keymap.resolve(PhysicalKey.SAVE).orElseThrow());
        assertFalse(keymap.resolve(PhysicalKey.OPEN).isPresent());
    }

    @Test
    void duplicateBindingFailsUntilCallerExplicitlyRebindsIt() {
        Keymap<PhysicalKey> keymap = new Keymap<>();
        CommandInvocation save = CommandInvocation.of("tuink.editor.save");
        CommandInvocation saveAs = CommandInvocation.of("tuink.editor.save-as");
        keymap.bind(PhysicalKey.SAVE, save);

        IllegalStateException error = assertThrows(
            IllegalStateException.class,
            () -> keymap.bind(PhysicalKey.SAVE, saveAs)
        );

        assertTrue(error.getMessage().contains("SAVE"));
        assertEquals(save, keymap.rebind(PhysicalKey.SAVE, saveAs).orElseThrow());
        assertEquals(saveAs, keymap.resolve(PhysicalKey.SAVE).orElseThrow());
    }

    @Test
    void bindingSnapshotsAreImmutableAndKeepDeclarationOrder() {
        Keymap<PhysicalKey> keymap = new Keymap<>();
        CommandInvocation save = CommandInvocation.of("tuink.editor.save");
        CommandInvocation open = CommandInvocation.of("tuink.editor.open");
        keymap.bind(PhysicalKey.SAVE, save);
        keymap.bind(PhysicalKey.OPEN, open);

        Map<PhysicalKey, CommandInvocation> bindings = keymap.bindings();

        assertEquals(
            java.util.List.of(PhysicalKey.SAVE, PhysicalKey.OPEN),
            java.util.List.copyOf(bindings.keySet())
        );
        assertThrows(UnsupportedOperationException.class, () -> bindings.put(PhysicalKey.SAVE, open));
    }

    @Test
    void unbindReturnsTheRemovedInvocation() {
        Keymap<PhysicalKey> keymap = new Keymap<>();
        CommandInvocation save = CommandInvocation.of("tuink.editor.save");
        keymap.bind(PhysicalKey.SAVE, save);

        assertEquals(save, keymap.unbind(PhysicalKey.SAVE).orElseThrow());
        assertFalse(keymap.unbind(PhysicalKey.SAVE).isPresent());
        assertEquals(0, keymap.size());
    }

    @Test
    void nullKeysAndInvocationsAreRejected() {
        Keymap<PhysicalKey> keymap = new Keymap<>();
        CommandInvocation save = CommandInvocation.of("tuink.editor.save");

        assertThrows(NullPointerException.class, () -> keymap.bind(null, save));
        assertThrows(NullPointerException.class, () -> keymap.bind(PhysicalKey.SAVE, null));
        assertThrows(NullPointerException.class, () -> keymap.resolve(null));
    }

    private enum PhysicalKey {
        SAVE,
        OPEN
    }
}
