package dev.tuink.nativeimage;

import com.oracle.svm.core.annotate.Substitute;
import com.oracle.svm.core.annotate.TargetClass;
import org.graalvm.nativeimage.PinnedObject;
import org.graalvm.nativeimage.Platform;
import org.graalvm.nativeimage.Platforms;
import org.graalvm.nativeimage.c.CContext;
import org.graalvm.nativeimage.c.function.CFunction;
import org.graalvm.nativeimage.c.function.CLibrary;
import org.graalvm.nativeimage.c.type.CCharPointer;
import org.graalvm.word.UnsignedWord;
import org.graalvm.word.WordFactory;

import java.util.List;
import java.util.Objects;

/** Uses the linked libc in native images, including fully static musl binaries. */
@TargetClass(className = "dev.tuink.storage.jvm.LinuxXattr")
@Platforms(Platform.LINUX.class)
final class LinuxXattrSubstitutions {
    @Substitute
    static int get(byte[] path, byte[] name, byte[] value) {
        try (PinnedObject pinnedPath = PinnedObject.create(path);
             PinnedObject pinnedName = PinnedObject.create(name);
             PinnedObject pinnedValue = PinnedObject.create(value)) {
            int size = value == null ? 0 : value.length;
            return LinuxXattrCalls.get(pinnedPath.addressOfArrayElement(0),
                    pinnedName.addressOfArrayElement(0),
                    size == 0 ? WordFactory.nullPointer() : pinnedValue.addressOfArrayElement(0),
                    WordFactory.unsigned(size));
        }
    }

    @Substitute
    static int set(byte[] path, byte[] name, byte[] value) {
        Objects.requireNonNull(value);
        try (PinnedObject pinnedPath = PinnedObject.create(path);
             PinnedObject pinnedName = PinnedObject.create(name);
             PinnedObject pinnedValue = PinnedObject.create(value)) {
            return LinuxXattrCalls.set(pinnedPath.addressOfArrayElement(0),
                    pinnedName.addressOfArrayElement(0),
                    value.length == 0 ? WordFactory.nullPointer() : pinnedValue.addressOfArrayElement(0),
                    WordFactory.unsigned(value.length));
        }
    }

    @Substitute
    static int remove(byte[] path, byte[] name) {
        try (PinnedObject pinnedPath = PinnedObject.create(path);
             PinnedObject pinnedName = PinnedObject.create(name)) {
            return LinuxXattrCalls.remove(pinnedPath.addressOfArrayElement(0),
                    pinnedName.addressOfArrayElement(0));
        }
    }
}

@CContext(LinuxXattrCalls.Headers.class)
@CLibrary(value = "tuink_xattr", requireStatic = true)
@Platforms(Platform.LINUX.class)
final class LinuxXattrCalls {
    // The C shim captures errno before a native-to-Java transition can change it.
    @CFunction("tuink_getxattr")
    static native int get(CCharPointer path, CCharPointer name, CCharPointer value, UnsignedWord size);

    @CFunction("tuink_setxattr")
    static native int set(CCharPointer path, CCharPointer name, CCharPointer value, UnsignedWord size);

    @CFunction("tuink_removexattr")
    static native int remove(CCharPointer path, CCharPointer name);

    public static final class Headers implements CContext.Directives {
        @Override
        public List<String> getHeaderFiles() {
            return List.of("\"tuink_xattr.h\"");
        }
    }
}
