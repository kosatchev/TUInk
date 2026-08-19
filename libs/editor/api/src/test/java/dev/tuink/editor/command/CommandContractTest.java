package dev.tuink.editor.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CommandContractTest {
    @Test
    void commandIdsMustBeStableAndNamespaced() {
        assertEquals("tuink.editor.save", CommandId.of("tuink.editor.save").value());
        assertThrows(IllegalArgumentException.class, () -> CommandId.of("save"));
        assertThrows(IllegalArgumentException.class, () -> CommandId.of("TUINK.editor.save"));
    }

    @Test
    void invocationOwnsAnImmutableArgumentSnapshot() {
        Map<String, String> mutable = new LinkedHashMap<>();
        mutable.put("tab", "3");
        CommandInvocation invocation = new CommandInvocation(
            CommandId.of("tuink.tabs.activate"),
            mutable
        );

        mutable.put("tab", "7");

        assertEquals("3", invocation.argument("tab").orElseThrow());
        assertFalse(invocation.argument("missing").isPresent());
        assertThrows(
            UnsupportedOperationException.class,
            () -> invocation.arguments().put("tab", "9")
        );
    }

    @Test
    void descriptorsRejectMissingPresentationMetadata() {
        CommandId id = CommandId.of("tuink.editor.save");
        assertEquals("", new CommandDescriptor(id, "Save").description());
        assertThrows(IllegalArgumentException.class, () -> new CommandDescriptor(id, " "));
    }
}
