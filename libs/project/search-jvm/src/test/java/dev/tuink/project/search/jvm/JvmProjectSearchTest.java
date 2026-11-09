package dev.tuink.project.search.jvm;

import static org.junit.jupiter.api.Assertions.*;
import dev.tuink.core.DocumentCodec;
import dev.tuink.editor.document.DocumentId;
import dev.tuink.editor.document.DocumentSnapshot;
import dev.tuink.editor.search.SearchOptions;
import dev.tuink.editor.storage.DocumentOpenMode;
import dev.tuink.project.search.ProjectSearch;
import dev.tuink.storage.jvm.JvmDocumentStorage;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JvmProjectSearchTest {
    @TempDir Path root;
    private List<ProjectSearch.Event> search(String query, JvmSearchSources.Options options,
                                            List<JvmSearchSources.Buffer> buffers, int limit) {
        var events = new ArrayList<ProjectSearch.Event>();
        ProjectSearch.run(new ProjectSearch.Request(query, SearchOptions.DEFAULT, limit),
            new JvmSearchSources(root, root, options, buffers), () -> false, events::add);
        return events;
    }
    private JvmSearchSources.Options options() { return new JvmSearchSources.Options("", "", true, true, DocumentOpenMode.DETECT, 64); }
    private List<ProjectSearch.Match> matches(List<ProjectSearch.Event> events) {
        return events.stream().filter(ProjectSearch.Matches.class::isInstance).map(ProjectSearch.Matches.class::cast)
            .flatMap(event -> event.matches().stream()).toList();
    }
    private ProjectSearch.Finished finished(List<ProjectSearch.Event> events) { return (ProjectSearch.Finished) events.getLast(); }

    @Test void scansClosedFilesWithMixedLineEndingsAndUtf16Positions() throws Exception {
        Files.writeString(root.resolve("text.txt"), "first\r\n🙂needle\rlast needle\n");
        var events = search("needle", options(), List.of(), 100);
        var found = matches(events);
        assertEquals(2, found.size());
        assertEquals(1, found.getFirst().line()); assertEquals(2, found.getFirst().start());
        assertEquals(2, found.getLast().line()); assertEquals(5, found.getLast().start());
        assertEquals(ProjectSearch.Outcome.COMPLETE, finished(events).outcome());
    }
    @Test void snapshotSupersedesDiskWithoutDuplicateResultsAndIncludesNamedNewFiles() throws Exception {
        Path file = Files.writeString(root.resolve("note.md"), "disk-only");
        var storage = JvmDocumentStorage.resource(file);
        var load = storage.storage().load(storage, DocumentOpenMode.DETECT, false);
        var binding = new dev.tuink.editor.storage.ResourceBinding(load.resource(), load.disposition(), load.baseline());
        var snapshot = new DocumentSnapshot(new DocumentId("draft"), 4, List.of("draft needle"));
        var source = new JvmSearchSources.Buffer(binding, snapshot, DocumentOpenMode.UTF_8);
        var events = search("needle", options(), List.of(source), 100);
        assertEquals(1, matches(events).size());
        assertEquals(snapshot.documentId(), matches(events).getFirst().target().documentId());
        assertEquals(4, matches(events).getFirst().target().revision());
        assertTrue(matches(search("disk-only", options(), List.of(source), 100)).isEmpty());
        var missing = JvmDocumentStorage.resource(root.resolve("new.md"));
        var newBinding = new dev.tuink.editor.storage.ResourceBinding(missing, dev.tuink.editor.storage.SaveDisposition.CREATE_NEW, null);
        assertEquals(1, matches(search("needle", options(), List.of(new JvmSearchSources.Buffer(newBinding, snapshot, DocumentOpenMode.UTF_8)), 100)).size());
    }
    @Test void honorsNestedIgnoreRulesNegationHiddenFilesAndPathMasks() throws Exception {
        Files.createDirectories(root.resolve("nested"));
        Files.writeString(root.resolve(".gitignore"), "*.log\nignored/\n");
        Files.writeString(root.resolve("nested/.gitignore"), "!keep.log\n");
        Files.writeString(root.resolve("nested/keep.log"), "needle");
        Files.writeString(root.resolve("nested/drop.log"), "needle");
        Files.writeString(root.resolve(".env"), "needle");
        Files.createDirectories(root.resolve("ignored"));
        Files.writeString(root.resolve("ignored/hidden.txt"), "needle");
        assertEquals(List.of(".env", "nested/keep.log"), matches(search("needle", options(), List.of(), 100))
            .stream().map(match -> match.target().label()).sorted().toList());
        var filtered = new JvmSearchSources.Options("*.log", "", true, false, DocumentOpenMode.DETECT, 64);
        assertEquals(List.of("nested/keep.log"), matches(search("needle", filtered, List.of(), 100)).stream().map(match -> match.target().label()).toList());
        var all = new JvmSearchSources.Options("*.log", "nested/drop.log", false, true, DocumentOpenMode.DETECT, 64);
        assertEquals(1, matches(search("needle", all, List.of(), 100)).size());
    }
    @Test void automaticAndExplicitEncodingsAgreeWithDocumentOpening() throws Exception {
        for (var mode : List.of(DocumentOpenMode.UTF_8, DocumentOpenMode.UTF_16LE, DocumentOpenMode.UTF_16BE,
            DocumentOpenMode.WINDOWS_1251, DocumentOpenMode.CP866, DocumentOpenMode.KOI8_R)) {
            String charset = switch (mode) {
                case UTF_8 -> "UTF-8"; case UTF_16LE -> "UTF-16LE"; case UTF_16BE -> "UTF-16BE";
                case WINDOWS_1251 -> "windows-1251"; case CP866 -> "IBM866"; case KOI8_R -> "KOI8-R"; default -> throw new AssertionError();
            };
            String text = "до\r\nпривет\rпосле";
            byte[] data = text.getBytes(Charset.forName(charset));
            if (mode == DocumentOpenMode.UTF_16LE || mode == DocumentOpenMode.UTF_16BE) {
                byte[] bom = mode == DocumentOpenMode.UTF_16LE ? new byte[]{-1,-2} : new byte[]{-2,-1};
                byte[] combined = new byte[data.length + 2]; System.arraycopy(bom,0,combined,0,2); System.arraycopy(data,0,combined,2,data.length); data = combined;
            }
            Path file = Files.write(root.resolve(mode.name() + ".txt"), data);
            var configured = new JvmSearchSources.Options(file.getFileName().toString(), "", false, true, mode, 64);
            var events = search("привет", configured, List.of(), 100);
            assertEquals(1, matches(events).size(), mode.name());
            assertEquals(1, matches(events).getFirst().line());
            assertEquals(mode, matches(events).getFirst().target().readVersion().mode());
        }
        var automatic = search("привет", options(), List.of(), 100);
        assertEquals(4, matches(automatic).size());
    }
    @Test void invalidUtf8NearTheEndUsesTheWholeFileFallback() throws Exception {
        byte[] bytes = ("a".repeat(70000) + "\nпривет").getBytes(Charset.forName("windows-1251"));
        Files.write(root.resolve("legacy.txt"), bytes);
        assertEquals(1, matches(search("привет", options(), List.of(), 100)).size());
    }
    @Test void invalidPathMasksReportFailureInsteadOfAnEmptySuccessfulSearch() throws Exception {
        Files.writeString(root.resolve("note.txt"), "needle");
        var invalid = new JvmSearchSources.Options("!*.txt", "", true, true, DocumentOpenMode.DETECT, 64);
        var events = search("needle", invalid, List.of(), 100);
        assertTrue(matches(events).isEmpty());
        assertEquals(ProjectSearch.Outcome.FAILED, finished(events).outcome());
    }
    @Test void anOpenedHardLinkUsesTheBufferForItsOtherNamesToo() throws Exception {
        Path file = Files.writeString(root.resolve("one.txt"), "disk needle");
        Files.createLink(root.resolve("two.txt"), file);
        var resource = JvmDocumentStorage.resource(file);
        var loaded = resource.storage().load(resource, DocumentOpenMode.DETECT, false);
        var binding = new dev.tuink.editor.storage.ResourceBinding(loaded.resource(), loaded.disposition(), loaded.baseline());
        var snapshot = new DocumentSnapshot(new DocumentId("hard-link-draft"), 1, List.of("draft needle"));
        var events = search("needle", options(), List.of(new JvmSearchSources.Buffer(binding, snapshot, DocumentOpenMode.UTF_8)), 100);
        assertEquals(1, matches(events).size());
        assertEquals(snapshot.documentId(), matches(events).getFirst().target().documentId());
    }
    @Test void anOpenBufferCannotReincludeAFileInsideAnExcludedParent() throws Exception {
        Path directory = Files.createDirectory(root.resolve("ignored"));
        Path file = Files.writeString(directory.resolve("note.txt"), "needle");
        Files.writeString(root.resolve(".gitignore"), "ignored/\n");
        Files.writeString(directory.resolve(".gitignore"), "!note.txt\n");
        var resource = JvmDocumentStorage.resource(file);
        var loaded = resource.storage().load(resource, DocumentOpenMode.DETECT, false);
        var binding = new dev.tuink.editor.storage.ResourceBinding(loaded.resource(), loaded.disposition(), loaded.baseline());
        var snapshot = new DocumentSnapshot(new DocumentId("ignored-draft"), 1, List.of("needle"));
        var events = search("needle", options(), List.of(new JvmSearchSources.Buffer(binding, snapshot, DocumentOpenMode.UTF_8)), 100);
        assertTrue(matches(events).isEmpty());
    }
    @Test void aSubfolderScopeCannotReincludeAnIgnoredParentOrFallBackToItsDiskCopy() throws Exception {
        Path scope = Files.createDirectory(root.resolve("ignored"));
        Path file = Files.writeString(scope.resolve("note.txt"), "disk needle");
        Files.writeString(root.resolve(".gitignore"), "ignored/\n");
        Files.writeString(scope.resolve(".gitignore"), "!note.txt\n");
        var resource = JvmDocumentStorage.resource(file);
        var loaded = resource.storage().load(resource, DocumentOpenMode.DETECT, false);
        var binding = new dev.tuink.editor.storage.ResourceBinding(loaded.resource(), loaded.disposition(), loaded.baseline());
        var snapshot = new DocumentSnapshot(new DocumentId("scoped-draft"), 1, List.of("draft needle"));
        var buffer = new JvmSearchSources.Buffer(binding, snapshot, DocumentOpenMode.UTF_8);
        for (var buffers : List.of(List.<JvmSearchSources.Buffer>of(), List.of(buffer))) {
            var events = new ArrayList<ProjectSearch.Event>();
            ProjectSearch.run(new ProjectSearch.Request("needle", SearchOptions.DEFAULT, 100),
                new JvmSearchSources(root, scope, options(), buffers), () -> false, events::add);
            assertTrue(matches(events).isEmpty(), "Selecting a narrower scope must retain the parent's exclusion");
            assertEquals(ProjectSearch.Outcome.COMPLETE, finished(events).outcome());
        }
        var included = new JvmSearchSources.Options("", "", false, true, DocumentOpenMode.DETECT, 64);
        var events = new ArrayList<ProjectSearch.Event>();
        ProjectSearch.run(new ProjectSearch.Request("needle", SearchOptions.DEFAULT, 100),
            new JvmSearchSources(root, scope, included, List.of(buffer)), () -> false, events::add);
        assertEquals(1, matches(events).size());
        assertEquals(snapshot.documentId(), matches(events).getFirst().target().documentId());
        assertEquals("draft needle", matches(events).getFirst().snippet());
    }
    @Test void aWorkingFolderInsideAnIgnoredRepositoryParentCannotReincludeItsFiles() throws Exception {
        Files.createDirectory(root.resolve(".git"));
        Path workspace = Files.createDirectories(root.resolve("ignored/workspace"));
        Files.writeString(root.resolve(".gitignore"), "ignored/\n");
        Files.writeString(root.resolve("ignored/.gitignore"), "!workspace/\n");
        Files.writeString(workspace.resolve(".gitignore"), "!note.txt\n");
        Files.writeString(workspace.resolve("note.txt"), "needle");
        var events = new ArrayList<ProjectSearch.Event>();
        ProjectSearch.run(new ProjectSearch.Request("needle", SearchOptions.DEFAULT, 100),
            new JvmSearchSources(workspace, workspace, options(), List.of()), () -> false, events::add);
        assertTrue(matches(events).isEmpty());
        assertEquals(ProjectSearch.Outcome.COMPLETE, finished(events).outcome());
    }
    @Test void aSymlinkedWorkingFolderUsesItsPhysicalRepositoryRulesAndKeepsLogicalResources(@TempDir Path shortcuts) throws Exception {
        Files.createDirectory(root.resolve(".git"));
        Path workspace = Files.createDirectory(root.resolve("src"));
        Path file = Files.writeString(workspace.resolve("note.txt"), "disk needle");
        Files.writeString(workspace.resolve("secret.log"), "needle");
        Files.writeString(root.resolve(".gitignore"), "*.log\n");
        // The repository containing the shortcut does not own the target files.
        Files.createDirectory(shortcuts.resolve(".git"));
        Files.writeString(shortcuts.resolve(".gitignore"), "workspace/\n");
        Path alias = Files.createSymbolicLink(shortcuts.resolve("workspace"), workspace);
        var events = new ArrayList<ProjectSearch.Event>();
        ProjectSearch.run(new ProjectSearch.Request("needle", SearchOptions.DEFAULT, 100),
            new JvmSearchSources(alias, alias, options(), List.of()), () -> false, events::add);
        assertEquals(List.of("note.txt"), matches(events).stream().map(match -> match.target().label()).toList());
        assertEquals(alias.resolve("note.txt"), JvmDocumentStorage.pathOf(matches(events).getFirst().target().resource()));

        var resource = JvmDocumentStorage.resource(file);
        var loaded = resource.storage().load(resource, DocumentOpenMode.DETECT, false);
        var binding = new dev.tuink.editor.storage.ResourceBinding(loaded.resource(), loaded.disposition(), loaded.baseline());
        var snapshot = new DocumentSnapshot(new DocumentId("real-path-draft"), 2, List.of("draft needle"));
        events.clear();
        ProjectSearch.run(new ProjectSearch.Request("needle", SearchOptions.DEFAULT, 100),
            new JvmSearchSources(alias, alias, options(), List.of(new JvmSearchSources.Buffer(binding, snapshot, DocumentOpenMode.UTF_8))),
            () -> false, events::add);
        assertEquals(1, matches(events).size());
        assertEquals(snapshot.documentId(), matches(events).getFirst().target().documentId());
        assertEquals("draft needle", matches(events).getFirst().snippet());
        Path nested = Files.createDirectory(workspace.resolve("nested"));
        Files.writeString(nested.resolve("note.txt"), "scoped");
        Files.writeString(nested.resolve("secret.log"), "scoped");
        events.clear();
        ProjectSearch.run(new ProjectSearch.Request("scoped", SearchOptions.DEFAULT, 100),
            new JvmSearchSources(alias, alias.resolve("nested"), options(), List.of()), () -> false, events::add);
        assertEquals(List.of("nested/note.txt"), matches(events).stream().map(match -> match.target().label()).toList());
        assertEquals(alias.resolve("nested/note.txt"), JvmDocumentStorage.pathOf(matches(events).getFirst().target().resource()));
    }
    @Test void aSymlinkedWorkingFolderCannotBypassAnIgnoredPhysicalParent(@TempDir Path shortcuts) throws Exception {
        Files.createDirectory(root.resolve(".git"));
        Path workspace = Files.createDirectory(root.resolve("ignored"));
        Files.writeString(root.resolve(".gitignore"), "ignored/\n");
        Files.writeString(workspace.resolve(".gitignore"), "!note.txt\n");
        Files.writeString(workspace.resolve("note.txt"), "needle");
        Path alias = Files.createSymbolicLink(shortcuts.resolve("workspace"), workspace);
        for (Path selected : List.of(workspace, alias)) {
            var events = new ArrayList<ProjectSearch.Event>();
            ProjectSearch.run(new ProjectSearch.Request("needle", SearchOptions.DEFAULT, 100),
                new JvmSearchSources(selected, selected, options(), List.of()), () -> false, events::add);
            assertTrue(matches(events).isEmpty(), selected.toString());
        }
    }
    @Test void binaryOversizedAndLongLineInputsReportIncompleteCoverage() throws Exception {
        Files.write(root.resolve("binary"), new byte[]{'n', 0, 'x'});
        Files.writeString(root.resolve("long.txt"), "x".repeat(ProjectSearch.MAX_LINE_LENGTH + 1));
        Files.writeString(root.resolve("large.txt"), "x".repeat(1024 * 1024 + 1));
        Files.writeString(root.resolve("good.txt"), "needle");
        var events = search("needle", new JvmSearchSources.Options("", "", true, true, DocumentOpenMode.DETECT, 1), List.of(), 100);
        assertEquals(1, matches(events).size());
        assertEquals(ProjectSearch.Outcome.PARTIAL, finished(events).outcome());
        assertEquals(3, finished(events).skipped());
    }
    @Test void resultLimitIsExplicitAndRangesStayCanonical() throws Exception {
        Files.writeString(root.resolve("many.txt"), "needle ".repeat(50));
        var events = search("needle", options(), List.of(), 3);
        assertEquals(3, matches(events).size());
        assertEquals(List.of(0,7,14), matches(events).stream().map(ProjectSearch.Match::start).toList());
        assertEquals(ProjectSearch.Outcome.LIMIT, finished(events).outcome());
    }
    @Test void doesNotFollowSymlinkCyclesOrReadSpecialSources() throws Exception {
        Files.writeString(root.resolve("good.txt"), "needle");
        Files.createSymbolicLink(root.resolve("cycle"), root);
        Files.createSymbolicLink(root.resolve("alias.txt"), root.resolve("good.txt"));
        assertEquals(1, matches(search("needle", options(), List.of(), 100)).size());
    }
    @Test void cancellationStopsInsideALongNonMatchingLine() throws Exception {
        Files.writeString(root.resolve("long.txt"), "x".repeat(700000));
        AtomicInteger checks = new AtomicInteger();
        var events = new ArrayList<ProjectSearch.Event>();
        ProjectSearch.run(new ProjectSearch.Request("absent", SearchOptions.DEFAULT, 100), new JvmSearchSources(root, root, options(), List.of()),
            () -> checks.incrementAndGet() > 100, events::add);
        assertTrue(events.stream().noneMatch(ProjectSearch.Finished.class::isInstance));
        assertTrue(checks.get() > 100);
    }
}
