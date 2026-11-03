package dev.tuink.storage.jvm;

import dev.tuink.editor.storage.DocumentResource;
import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Test-only access to the JVM provider's existing publication seam. */
public final class BlockingPublicationStorage {
    private final CountDownLatch publishing = new CountDownLatch(1);
    private final CountDownLatch release = new CountDownLatch(1);
    private final JvmDocumentStorage storage;

    public BlockingPublicationStorage(boolean failAfterPublication) {
        storage = new JvmDocumentStorage(new JvmDocumentStorage.PublicationFiles() {
            @Override public void move(Path staged, Path target, boolean atomic) throws IOException {
                // JvmDocumentStorage fences publication immediately before this call.
                publishing.countDown();
                try {
                    if (!release.await(15, TimeUnit.SECONDS)) throw new IOException("Publication fixture was not released");
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Publication fixture was interrupted", interrupted);
                }
                JvmDocumentStorage.PublicationFiles.super.move(staged, target, atomic);
                if (failAfterPublication) throw new IOException("Acknowledgement lost after the real publication");
            }
        });
    }

    public DocumentResource resource(Path path) { return storage.fileResource(path); }
    public boolean awaitPublication() throws InterruptedException { return publishing.await(5, TimeUnit.SECONDS); }
    public void release() { release.countDown(); }
}
