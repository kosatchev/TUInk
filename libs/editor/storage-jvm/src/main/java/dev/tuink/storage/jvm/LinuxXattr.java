package dev.tuink.storage.jvm;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;

/** libc xattr calls. Native Image substitutes these methods with static C bindings. */
final class LinuxXattr {
    private LinuxXattr() {}

    static int get(byte[] path, byte[] name, byte[] value) throws IOException {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment state = arena.allocate(Ffm.CALL_STATE);
            int capacity = value == null ? 0 : value.length;
            MemorySegment buffer = capacity == 0 ? MemorySegment.NULL : arena.allocate(capacity);
            long result = (long) Ffm.GET.invokeExact(state, arena.allocateFrom(ValueLayout.JAVA_BYTE, path),
                arena.allocateFrom(ValueLayout.JAVA_BYTE, name), buffer, (long) capacity);
            if (result < 0) return -state.get(ValueLayout.JAVA_INT, Ffm.ERRNO_OFFSET);
            if (result > Integer.MAX_VALUE || capacity != 0 && result > capacity) {
                throw new IOException("Invalid Linux extended-attribute size");
            }
            if (capacity != 0) MemorySegment.copy(buffer, ValueLayout.JAVA_BYTE, 0, value, 0, (int) result);
            return (int) result;
        } catch (Throwable failure) {
            throw unavailable(failure);
        }
    }

    static int set(byte[] path, byte[] name, byte[] value) throws IOException {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment state = arena.allocate(Ffm.CALL_STATE);
            MemorySegment buffer = value.length == 0 ? MemorySegment.NULL : arena.allocateFrom(ValueLayout.JAVA_BYTE, value);
            int result = (int) Ffm.SET.invokeExact(state, arena.allocateFrom(ValueLayout.JAVA_BYTE, path),
                arena.allocateFrom(ValueLayout.JAVA_BYTE, name), buffer, (long) value.length, 0);
            return result < 0 ? -state.get(ValueLayout.JAVA_INT, Ffm.ERRNO_OFFSET) : result;
        } catch (Throwable failure) {
            throw unavailable(failure);
        }
    }

    static int remove(byte[] path, byte[] name) throws IOException {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment state = arena.allocate(Ffm.CALL_STATE);
            int result = (int) Ffm.REMOVE.invokeExact(state, arena.allocateFrom(ValueLayout.JAVA_BYTE, path),
                arena.allocateFrom(ValueLayout.JAVA_BYTE, name));
            return result < 0 ? -state.get(ValueLayout.JAVA_INT, Ffm.ERRNO_OFFSET) : result;
        } catch (Throwable failure) {
            throw unavailable(failure);
        }
    }

    private static IOException unavailable(Throwable failure) {
        if (failure instanceof IOException io) return io;
        if (failure instanceof Error error && !(error instanceof LinkageError)) throw error;
        return new IOException("Linux extended-attribute access is unavailable", failure);
    }

    private static final class Ffm {
        private static final Linker LINKER = Linker.nativeLinker();
        private static final MemoryLayout CALL_STATE = Linker.Option.captureStateLayout();
        private static final long ERRNO_OFFSET = CALL_STATE.byteOffset(MemoryLayout.PathElement.groupElement("errno"));
        private static final MethodHandle GET = bind("getxattr", FunctionDescriptor.of(ValueLayout.JAVA_LONG,
            ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG));
        private static final MethodHandle SET = bind("setxattr", FunctionDescriptor.of(ValueLayout.JAVA_INT,
            ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT));
        private static final MethodHandle REMOVE = bind("removexattr", FunctionDescriptor.of(ValueLayout.JAVA_INT,
            ValueLayout.ADDRESS, ValueLayout.ADDRESS));

        private static MethodHandle bind(String name, FunctionDescriptor descriptor) {
            if (LINKER.canonicalLayouts().get("size_t").byteSize() != Long.BYTES) {
                throw new UnsupportedOperationException("Linux xattr requires a 64-bit platform");
            }
            return LINKER.downcallHandle(LINKER.defaultLookup().find(name).orElseThrow(), descriptor,
                Linker.Option.captureCallState("errno"));
        }
    }
}
