package dev.tuiframe.explorer.file;

import dev.tuiframe.explorer.Explorer;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Explorer-owned filesystem admission and bounded session history.
 *
 * <p>Mutating methods run on an I/O worker. Read-only history queries never wait
 * for that worker, so a menu or frame remains responsive during long transfers.</p>
 */
public final class ExplorerOperations {
    private static final int MAX_HISTORY = 100;
    private static final int MAX_COPY_ENTRIES = 10_000;
    private static final int MAX_GUARD_ENTRIES = 40_000;
    public record HistoryEntry(ExplorerFileService.Action action, Path source, Path destination) {}
    private record Copied(ExplorerFileService.CopyGuard source, ExplorerFileService.CopyGuard destination) {
        int weight() { return source.size() + destination.size(); }
    }
    private record Step(ExplorerFileService.Action action, Path before, Path after,
        ExplorerFileService.Target expected, Copied copied) {
        boolean creation() { return action == ExplorerFileService.Action.CREATE_FILE || action == ExplorerFileService.Action.CREATE_DIRECTORY; }
        boolean copying() { return action == ExplorerFileService.Action.COPY; }
        Step expecting(ExplorerFileService.Target target) { return new Step(action, before, after, target, copied); }
        Step copied(Copied guards) { return new Step(action, before, after, expected, guards); }
        int weight() { return copied == null ? 1 : copied.weight(); }
    }
    private record History(List<Step> undo, List<Step> redo, String barrier, long version) {
        History { undo = List.copyOf(undo); redo = List.copyOf(redo); }
    }

    private final Explorer.Access access;
    private final ExplorerFileService files;
    private final int maximumCopyEntries;
    private final int maximumGuardEntries;
    private final AtomicBoolean busy = new AtomicBoolean();
    private volatile History history = new History(List.of(), List.of(), null, 0);
    private volatile Path protectedRoot;

    public ExplorerOperations(Explorer.Access access, ExplorerFileService files) {
        this(access, files, MAX_COPY_ENTRIES, MAX_GUARD_ENTRIES);
    }

    ExplorerOperations(Explorer.Access access, ExplorerFileService files, int maximumCopyEntries, int maximumGuardEntries) {
        this.access = Objects.requireNonNull(access, "access");
        this.files = Objects.requireNonNull(files, "files");
        if (maximumCopyEntries < 1 || maximumGuardEntries < 2 * maximumCopyEntries)
            throw new IllegalArgumentException("Copy history limits must accommodate a complete source and destination guard");
        this.maximumCopyEntries = maximumCopyEntries;
        this.maximumGuardEntries = maximumGuardEntries;
    }

    public void setProtectedRoot(Path root) { protectedRoot = root == null ? null : root.toAbsolutePath().normalize(); }
    public boolean busy() { return busy.get(); }
    public boolean canUndo() { return writable() && !busy() && !history.undo().isEmpty(); }
    public boolean canRedo() { return writable() && !busy() && !history.redo().isEmpty(); }
    public long historyVersion() { return history.version(); }
    public String undoLabel() { var h = history; return h.undo().isEmpty() ? "Undo" : "Undo " + label(h.undo().getFirst().action()); }
    public String redoLabel() { var h = history; return h.redo().isEmpty() ? "Redo" : "Redo " + label(h.redo().getFirst().action()); }
    public String historyBarrier() { return history.barrier(); }
    public ExplorerFileService.Target inspect(Path path) throws IOException { return files.inspect(path); }
    public TrashProvider.Availability trashAvailability(Path path) { return files.trashAvailability(path); }

    public HistoryEntry undoEntry() {
        var h = history;
        if (h.undo().isEmpty()) return null;
        Step step = h.undo().getFirst();
        return new HistoryEntry(step.creation() || step.copying() ? ExplorerFileService.Action.DELETE : step.action(),
            step.after(), step.before());
    }

    public HistoryEntry redoEntry() {
        var h = history;
        if (h.redo().isEmpty()) return null;
        Step step = h.redo().getFirst();
        return new HistoryEntry(step.action(), step.creation() ? null : step.before(), step.after());
    }

    public ExplorerFileService.Result perform(ExplorerFileService.Action action, ExplorerFileService.Target target,
        Path parent, String name) {
        if (!writable()) return failed(action, target == null ? null : target.path(), "This explorer is read-only");
        if (!busy.compareAndSet(false, true)) return failed(action, target == null ? null : target.path(), "A file operation is already in progress");
        try {
            if ((action.relocates() || action.removes()) && target != null && protectedEntry(target.path()))
                return failed(action, target.path(), "The open root folder cannot be renamed, moved or deleted");
            if (action == ExplorerFileService.Action.COPY && target != null && target.path().getFileName() == null)
                return failed(action, target.path(), "The filesystem root cannot be copied");
            ExplorerFileService.CopyGuard copySource = null;
            String copyUnavailable = null;
            if (action == ExplorerFileService.Action.COPY && target != null) {
                try { copySource = files.captureCopyGuard(target.path(), maximumCopyEntries); }
                catch (IOException | RuntimeException unavailable) { copyUnavailable = unavailable.getMessage(); }
            }
            ExplorerFileService.Result result = execute(action, target, parent, name);
            acceptNew(result, copySource, copyUnavailable);
            return result;
        } catch (IOException | RuntimeException failure) {
            return failed(action, target == null ? null : target.path(), failure);
        } finally { busy.set(false); }
    }

    public ExplorerFileService.Result undo() { return traverse(true); }
    public ExplorerFileService.Result redo() { return traverse(false); }

    /**
     * Accepts a host-confirmed atomic content save at this path. This method is
     * worker-only and must not be called for an ordinary filesystem refresh.
     * Nonempty created files remain guarded against inverse deletion.
     */
    public boolean refreshIdentityAfterSave(Path path) {
        if (!writable() || !busy.compareAndSet(false, true)) return false;
        try {
            ExplorerFileService.Target saved = files.inspect(path);
            var h = history;
            List<Step> undo = replaceSavedIdentity(h.undo(), saved);
            List<Step> redo = replaceSavedIdentity(h.redo(), saved);
            if (undo.equals(h.undo()) && redo.equals(h.redo())) return false;
            history = new History(undo, redo, h.barrier(), h.version() + 1);
            return true;
        } catch (IOException | RuntimeException ignored) { return false; }
        finally { busy.set(false); }
    }

    private ExplorerFileService.Result traverse(boolean reverse) {
        if (!writable()) return failed(ExplorerFileService.Action.MOVE, null, "This explorer is read-only");
        if (!busy.compareAndSet(false, true)) return failed(ExplorerFileService.Action.MOVE, null, "A file operation is already in progress");
        try {
            History before = history;
            List<Step> from = reverse ? before.undo() : before.redo();
            if (from.isEmpty()) return failed(ExplorerFileService.Action.MOVE, null,
                reverse && before.barrier() != null ? before.barrier() : "Nothing to " + (reverse ? "undo" : "redo"));
            Step step = from.getFirst();
            Path source = reverse ? step.after() : step.before();
            if (source != null && !(step.copying() && !reverse) && protectedEntry(source))
                return failed(step.action(), source, "The open root folder cannot be changed by history");
            ExplorerFileService.Result result;
            if (step.copying()) {
                if (reverse) result = files.removeUnchangedCopy(step.copied().destination());
                else {
                    try { files.requireUnchangedCopy(step.copied().source()); }
                    catch (IOException | RuntimeException changed) {
                        return failed(step.action(), source, "The copy source changed; copy it again to use its new contents");
                    }
                    result = files.copy(step.copied().source().sourceTarget(), step.after().getParent(), step.copied().source());
                }
            } else if (step.creation()) {
                result = reverse ? files.removeEmptyCreated(step.expected())
                    : files.create(step.after().getParent(), step.after().getFileName().toString(),
                        step.action() == ExplorerFileService.Action.CREATE_DIRECTORY);
            } else {
                Path destination = reverse ? step.before() : step.after();
                result = step.action() == ExplorerFileService.Action.RENAME
                    ? files.rename(step.expected(), destination.getFileName().toString())
                    : files.move(step.expected(), destination.getParent());
            }
            if (!result.succeeded()) {
                if (result.partiallyChanged()) barrier("A partial file operation ended the undo history");
                return result;
            }
            ExplorerFileService.Target next;
            try { next = result.destination() == null ? null : files.publishedTarget(result); }
            catch (IOException | RuntimeException unavailable) {
                barrier("The file changed while updating its undo history");
                return result;
            }
            Step reciprocal = step.expecting(next == null && step.creation() ? step.expected() : next);
            ExplorerFileService.CopyGuard recreatedCopy = null;
            if (step.copying() && !reverse) {
                try {
                    var destination = files.captureCopyGuard(result.destination(), maximumCopyEntries);
                    files.requirePublishedIdentities(result);
                    if (!step.copied().source().sameContents(destination)) throw new IOException("The copy changed before its undo history was recorded");
                    reciprocal = reciprocal.copied(new Copied(step.copied().source(), destination));
                    recreatedCopy = destination;
                } catch (IOException | RuntimeException unavailable) {
                    barrier("The copy changed before its undo history was recorded");
                    return result;
                }
            }
            List<Step> remaining = new ArrayList<>(from.subList(1, from.size()));
            List<Step> other = new ArrayList<>(reverse ? before.redo() : before.undo());
            other.addFirst(reciprocal);
            List<ExplorerFileService.IdentityChange> identities = result.action().relocates() ? result.identities() : List.of();
            if (step.creation() && !reverse) identities = List.of(new ExplorerFileService.IdentityChange(step.expected(), next));
            else if (recreatedCopy != null) identities = step.copied().destination().recreatedAs(recreatedCopy);
            var changes = indexIdentities(identities);
            Path movedSource = result.action().relocates() ? result.source() : null;
            Path movedDestination = result.action().relocates() ? result.destination() : null;
            remaining = reidentify(remaining, changes, movedSource, movedDestination);
            other = reidentify(other, changes, movedSource, movedDestination);
            Path removed = result.action() == ExplorerFileService.Action.COPY ? null : result.source();
            remaining = refreshCopyGuards(remaining, removed, result.destination());
            other = refreshCopyGuards(other, removed, result.destination());
            history = reverse ? new History(remaining, other, before.barrier(), before.version() + 1)
                : new History(other, remaining, before.barrier(), before.version() + 1);
            return result;
        } catch (RuntimeException failure) {
            barrier("The file changed while updating its undo history");
            return failed(ExplorerFileService.Action.MOVE, null, failure);
        } finally { busy.set(false); }
    }

    private ExplorerFileService.Result execute(ExplorerFileService.Action action, ExplorerFileService.Target target,
        Path parent, String name) throws IOException {
        return switch (action) {
            case CREATE_FILE -> files.create(parent, name, false);
            case CREATE_DIRECTORY -> files.create(parent, name, true);
            case COPY -> files.copy(Objects.requireNonNull(target, "target"), parent);
            case MOVE -> {
                Objects.requireNonNull(target, "target");
                if (parent.toAbsolutePath().normalize().resolve(target.path().getFileName()).equals(target.path()))
                    yield new ExplorerFileService.Result(action, target.path(), target.path(), true, false, null);
                yield files.move(target, parent);
            }
            case RENAME -> files.rename(Objects.requireNonNull(target, "target"), name);
            case DELETE -> files.delete(Objects.requireNonNull(target, "target"));
            case TRASH -> files.trash(Objects.requireNonNull(target, "target"));
        };
    }

    private void acceptNew(ExplorerFileService.Result result, ExplorerFileService.CopyGuard copySource,
        String copyUnavailable) throws IOException {
        if (!result.succeeded()) {
            if (result.partiallyChanged()) barrier("A partial file operation ended the undo history");
            return;
        }
        if (result.action().removes()) { barrier("Deletion cannot be undone"); return; }
        if (!result.destinationPublished()) return;
        ExplorerFileService.Target next;
        try { next = files.publishedTarget(result); }
        catch (IOException | RuntimeException unavailable) { barrier("The destination changed before its undo history was recorded"); return; }
        Copied copied = null;
        if (result.action() == ExplorerFileService.Action.COPY) {
            if (copySource == null) {
                barrier(copyUnavailable == null ? "The copy cannot be safely recorded in file undo history" : copyUnavailable);
                return;
            }
            try {
                var destination = files.captureCopyGuard(result.destination(), maximumCopyEntries);
                files.requirePublishedIdentities(result);
                if (!copySource.sameContents(destination)) throw new IOException("The copy changed before its undo history was recorded");
                copied = new Copied(copySource, destination);
            } catch (IOException | RuntimeException unavailable) { barrier(unavailable.getMessage()); return; }
        }
        History before = history;
        Path movedSource = result.action().relocates() ? result.source() : null;
        Path movedDestination = result.action().relocates() ? result.destination() : null;
        var retained = reidentify(before.undo(), indexIdentities(result.action().relocates() ? result.identities() : List.of()),
            movedSource, movedDestination);
        var undo = new ArrayList<>(refreshCopyGuards(retained, movedSource, result.destination()));
        undo.addFirst(new Step(result.action(), result.source(), result.destination(), next, copied));
        int weight = undo.stream().mapToInt(Step::weight).sum();
        while (undo.size() > MAX_HISTORY || weight > maximumGuardEntries) weight -= undo.removeLast().weight();
        history = new History(undo, List.of(), before.barrier(), before.version() + 1);
    }

    private List<Step> replaceSavedIdentity(List<Step> steps, ExplorerFileService.Target current) {
        var result = new ArrayList<Step>(steps.size());
        for (Step step : steps) {
            if (step.expected() != null && step.expected().path().equals(current.path())
                && !step.copying()
                && (!step.creation() || current.size() == 0 && !current.link() && !current.directory()))
                step = step.expecting(current);
            result.add(step);
        }
        return result;
    }

    private static Map<Path, ExplorerFileService.IdentityChange> indexIdentities(List<ExplorerFileService.IdentityChange> identities) {
        var indexed = new HashMap<Path, ExplorerFileService.IdentityChange>();
        for (var change : identities) indexed.put(change.before().path(), change);
        return indexed;
    }

    /** Both stacks retain current identities while their original before/after paths remain replay intents. */
    private List<Step> reidentify(List<Step> steps, Map<Path, ExplorerFileService.IdentityChange> identities,
        Path source, Path destination) {
        var result = new ArrayList<Step>(steps.size());
        for (Step step : steps) {
            if (step.expected() != null) step = step.expecting(ExplorerFileService.reidentified(step.expected(),
                identities, source, destination, step.creation()));
            if (step.copying()) step = step.copied(new Copied(
                step.copied().source().reidentified(identities, source, destination),
                step.copied().destination().reidentified(identities, source, destination)));
            result.add(step);
        }
        return result;
    }

    /** Record only directory metadata changed by this operation, even while an old copy layout is not yet restored. */
    private List<Step> refreshCopyGuards(List<Step> steps, Path source, Path destination) {
        var parents = new HashMap<Path, ExplorerFileService.Target>();
        for (Path path : new Path[] {source, destination}) {
            if (path == null || path.getParent() == null || parents.containsKey(path.getParent())) continue;
            try { parents.put(path.getParent(), files.inspect(path.getParent())); }
            catch (IOException | RuntimeException unavailable) { /* An unproved parent retains its old guard. */ }
        }
        var refreshed = new ArrayList<Step>(steps.size());
        for (Step step : steps) {
            if (step.copying()) step = step.copied(new Copied(
                step.copied().source().refreshDirectoryTimes(parents),
                step.copied().destination().refreshDirectoryTimes(parents)));
            refreshed.add(step);
        }
        return refreshed;
    }

    private void barrier(String reason) {
        History before = history;
        history = new History(List.of(), List.of(), reason == null || reason.isBlank()
            ? "The operation cannot be safely recorded in file undo history" : reason, before.version() + 1);
    }
    private boolean writable() { return access == Explorer.Access.MANAGE_FILES; }
    private boolean protectedEntry(Path path) {
        Path root = protectedRoot;
        if (root == null) return false;
        if (path.toAbsolutePath().normalize().equals(root)) return true;
        try { return !Files.isSymbolicLink(path) && path.toRealPath().equals(root.toRealPath()); }
        catch (IOException | RuntimeException unavailable) { return false; }
    }
    private static String label(ExplorerFileService.Action action) {
        return switch (action) {
            case CREATE_FILE -> "Create file"; case CREATE_DIRECTORY -> "Create folder";
            case COPY -> "Copy"; case MOVE -> "Move"; case RENAME -> "Rename"; case DELETE, TRASH -> "Delete";
        };
    }
    private static ExplorerFileService.Result failed(ExplorerFileService.Action action, Path source, String reason) {
        return failed(action, source, new IOException(reason));
    }
    private static ExplorerFileService.Result failed(ExplorerFileService.Action action, Path source, Exception failure) {
        return new ExplorerFileService.Result(action, source, null, false, false,
            failure instanceof IOException io ? io : new IOException(failure.getMessage(), failure));
    }
}
