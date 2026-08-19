package dev.tuink.tui.command;

import dev.tuink.editor.command.CommandDescriptor;
import dev.tuink.editor.command.CommandId;
import dev.tuink.editor.command.CommandInvocation;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CommandTypesTest {
    @Test
    void commandIdsAreStableNamespacedValues() {
        CommandId save = CommandId.of("tuink.editor.save");

        assertAll(
            () -> assertEquals("tuink.editor.save", save.value()),
            () -> assertEquals("tuink.editor.save", save.toString()),
            () -> assertEquals(save, new CommandId("tuink.editor.save")),
            () -> assertEquals("com.example-plugin.run_task", CommandId.of("com.example-plugin.run_task").value())
        );
    }

    @Test
    void commandIdsRejectAmbiguousOrUnnamespacedValues() {
        assertAll(
            () -> assertThrows(IllegalArgumentException.class, () -> CommandId.of(null)),
            () -> assertThrows(IllegalArgumentException.class, () -> CommandId.of("")),
            () -> assertThrows(IllegalArgumentException.class, () -> CommandId.of("save")),
            () -> assertThrows(IllegalArgumentException.class, () -> CommandId.of("TUINK.editor.save")),
            () -> assertThrows(IllegalArgumentException.class, () -> CommandId.of("tuink..save")),
            () -> assertThrows(IllegalArgumentException.class, () -> CommandId.of(" tuink.editor.save")),
            () -> assertThrows(IllegalArgumentException.class, () -> CommandId.of("tuink.editor.save-"))
        );
    }

    @Test
    void invocationSnapshotsArgumentsAndSupportsLookup() {
        Map<String, String> mutable = new LinkedHashMap<>();
        mutable.put("tab", "3");
        CommandInvocation invocation = new CommandInvocation(
            CommandId.of("tuink.tabs.activate"),
            mutable
        );

        mutable.put("tab", "7");

        assertAll(
            () -> assertEquals("3", invocation.argument("tab").orElseThrow()),
            () -> assertFalse(invocation.argument("missing").isPresent()),
            () -> assertFalse(invocation.argument(null).isPresent()),
            () -> assertThrows(
                UnsupportedOperationException.class,
                () -> invocation.arguments().put("tab", "9")
            )
        );
    }

    @Test
    void invocationRejectsInvalidArgumentMaps() {
        Map<String, String> nullName = new LinkedHashMap<>();
        nullName.put(null, "value");
        Map<String, String> nullValue = new LinkedHashMap<>();
        nullValue.put("name", null);

        assertAll(
            () -> assertThrows(
                IllegalArgumentException.class,
                () -> new CommandInvocation(null, Map.of())
            ),
            () -> assertThrows(
                IllegalArgumentException.class,
                () -> new CommandInvocation(CommandId.of("tuink.test.run"), null)
            ),
            () -> assertThrows(
                IllegalArgumentException.class,
                () -> new CommandInvocation(CommandId.of("tuink.test.run"), Map.of(" ", "value"))
            ),
            () -> assertThrows(
                IllegalArgumentException.class,
                () -> new CommandInvocation(CommandId.of("tuink.test.run"), nullName)
            ),
            () -> assertThrows(
                IllegalArgumentException.class,
                () -> new CommandInvocation(CommandId.of("tuink.test.run"), nullValue)
            )
        );
    }

    @Test
    void descriptorRequiresPresentationMetadataButAllowsAnEmptyDescription() {
        CommandDescriptor descriptor = new CommandDescriptor(
            CommandId.of("tuink.editor.save"),
            "Save"
        );

        assertAll(
            () -> assertEquals("Save", descriptor.title()),
            () -> assertEquals("", descriptor.description()),
            () -> assertThrows(
                IllegalArgumentException.class,
                () -> new CommandDescriptor(null, "Save")
            ),
            () -> assertThrows(
                IllegalArgumentException.class,
                () -> new CommandDescriptor(descriptor.id(), " ")
            ),
            () -> assertThrows(
                IllegalArgumentException.class,
                () -> new CommandDescriptor(descriptor.id(), "Save", null)
            )
        );
    }

    @Test
    void invocationFactoriesCreateArgumentFreeRequests() {
        CommandInvocation fromText = CommandInvocation.of("tuink.editor.save");
        CommandInvocation fromId = CommandInvocation.of(fromText.id());

        assertTrue(fromText.arguments().isEmpty());
        assertEquals(fromText, fromId);
    }
}
