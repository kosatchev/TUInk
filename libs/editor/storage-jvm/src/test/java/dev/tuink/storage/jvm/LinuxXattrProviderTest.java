package dev.tuink.storage.jvm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import dev.tuink.storage.jvm.JvmDocumentStorage;
import dev.tuink.storage.jvm.spi.LinuxXattrAccess;
import java.util.List;
import java.util.ServiceLoader;
import org.junit.jupiter.api.Test;

class LinuxXattrProviderTest {
    @Test
    void theRuntimeServiceDescriptorRegistersExactlyTheJvmAdapter() {
        var services = ServiceLoader.load(LinuxXattrAccess.class).stream().toList();

        assertEquals(List.of(LinuxXattrProvider.class), services.stream().map(ServiceLoader.Provider::type).toList());
        assertInstanceOf(LinuxXattrProvider.class, services.getFirst().get());
    }
}
