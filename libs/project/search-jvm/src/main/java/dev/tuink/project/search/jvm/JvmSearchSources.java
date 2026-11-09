package dev.tuink.project.search.jvm;

import dev.tuink.editor.document.DocumentSnapshot;
import dev.tuink.editor.storage.DocumentOpenMode;
import dev.tuink.editor.storage.ResourceBinding;
import dev.tuink.project.search.ProjectSearch;
import dev.tuink.storage.jvm.JvmDocumentStorage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;
import org.eclipse.jgit.ignore.FastIgnoreRule;
import org.eclipse.jgit.ignore.IgnoreNode;

/** A detached source inventory. Filesystem identities and lookups stay in this adapter. */
public final class JvmSearchSources implements ProjectSearch.Sources {
    public record Buffer(ResourceBinding binding, DocumentSnapshot snapshot, DocumentOpenMode mode) {}
    public record Options(String includes, String excludes, boolean respectIgnore, boolean hidden,
                          DocumentOpenMode encoding, int maximumFileMiB) {}
    private record Ignore(Path directory, IgnoreNode rules) {}
    private final Path root;
    private final Path scope;
    private final Options options;
    private final List<Buffer> buffers;
    private final Function<Path, String> labels;

    public JvmSearchSources(Path root, Path scope, Options options, List<Buffer> buffers) {
        this(root, scope, options, buffers, path -> slash(root.toAbsolutePath().normalize().relativize(path)));
    }
    public JvmSearchSources(Path root, Path scope, Options options, List<Buffer> buffers, Function<Path, String> labels) {
        this.root = root.toAbsolutePath().normalize();
        this.scope = scope.toAbsolutePath().normalize();
        if (!this.scope.startsWith(this.root)) throw new IllegalArgumentException("Search scope must be inside the working folder");
        this.options = options; this.buffers = List.copyOf(buffers);
        this.labels = labels;
    }
    @Override public void visit(ProjectSearch.Control control, ProjectSearch.SourceVisitor visitor) throws IOException {
        Path physicalRoot = root.toRealPath();
        Path physicalScope = scope.toRealPath();
        if (!physicalScope.startsWith(physicalRoot)) throw new IOException("Search scope points outside the working folder");
        // Repository rules follow physical ancestry; result paths retain the chosen working root.
        Function<Path, Path> physicalPaths = root.equals(physicalRoot) ? Function.identity()
            : path -> path.startsWith(root) ? physicalRoot.resolve(root.relativize(path)) : path;
        var includes = masks(options.includes());
        var excludes = masks(options.excludes());
        ArrayList<Ignore> inherited = new ArrayList<>();
        if (options.respectIgnore()) {
            Path repository = physicalRoot;
            for (Path directory = physicalRoot; directory != null; directory = directory.getParent()) {
                if (Files.exists(directory.resolve(".git"), LinkOption.NOFOLLOW_LINKS)) { repository = directory; break; }
            }
            for (Path directory = repository; !directory.equals(physicalRoot); ) {
                addIgnore(directory, inherited, visitor, control, physicalPaths);
                Path relative = directory.relativize(physicalRoot);
                directory = directory.resolve(relative.getName(0));
                // A narrower scope must not load rules from an excluded parent:
                // Git cannot re-include descendants without including that parent.
                if (ignored(directory, true, inherited)) return;
            }
        }
        if (!scope.equals(root)) {
            Path directory = root;
            for (Path name : root.relativize(scope)) {
                if (options.respectIgnore()) addIgnore(directory, inherited, visitor, control, physicalPaths);
                directory = directory.resolve(name);
                if (!accepted(directory, true, includes, excludes, inherited, physicalPaths)) return;
            }
        }
        Set<String> covered = new HashSet<>();
        Map<Object, Path> physicalBuffers = new HashMap<>();
        for (Buffer buffer : buffers) {
            control.checkpoint();
            Path named = JvmDocumentStorage.pathOf(buffer.binding().resource());
            if (named == null) continue;
            Path path = named.toAbsolutePath().normalize();
            Path physical = JvmDocumentStorage.boundPhysicalPath(buffer.binding());
            if (physical == null) physical = path;
            Path relative = path.startsWith(scope) ? scope.relativize(path)
                : physical.startsWith(physicalScope) ? physicalScope.relativize(physical) : null;
            if (relative == null) continue;
            Path displayed = scope.resolve(relative);
            var rules = new ArrayList<>(inherited);
            if (options.respectIgnore()) {
                Path directory = scope;
                for (int i = 0; i < relative.getNameCount(); i++) {
                    addIgnore(directory, rules, visitor, control, physicalPaths);
                    if (i < relative.getNameCount() - 1) directory = directory.resolve(relative.getName(i));
                }
            }
            boolean parentsAccepted = true;
            Path parent = root;
            Path displayedRelative = root.relativize(displayed);
            for (int i = 0; i < displayedRelative.getNameCount() - 1; i++) {
                parent = parent.resolve(displayedRelative.getName(i));
                if (!accepted(parent, true, includes, excludes, rules, physicalPaths)) { parentsAccepted = false; break; }
            }
            if (!parentsAccepted || !accepted(displayed, false, includes, excludes, rules, physicalPaths)) continue;
            String key = physical.toUri().toASCIIString();
            if (!covered.add(key)) continue;
            try {
                Object fileKey = Files.readAttributes(physical, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS).fileKey();
                if (fileKey != null) physicalBuffers.put(fileKey, physical);
            } catch (IOException unavailable) { /* A named draft may not exist on disk yet. */ }
            visitor.accept(ProjectSearch.snapshot(key, label(displayed), buffer.binding().resource(), buffer.snapshot(), buffer.mode()));
        }
        long[] visited = {0};
        walk(scope, inherited, includes, excludes, covered, physicalBuffers, control, visitor, visited, 0, physicalPaths);
    }
    private void walk(Path directory, List<Ignore> inherited, List<FastIgnoreRule> includes,
                      List<FastIgnoreRule> excludes, Set<String> covered, Map<Object, Path> physicalBuffers, ProjectSearch.Control control,
                      ProjectSearch.SourceVisitor visitor, long[] visited, int depth, Function<Path, Path> physicalPaths) throws IOException {
        control.checkpoint();
        if (depth > 128) { issue(directory, "Directory depth limit reached", visitor); return; }
        ArrayList<Ignore> rules = new ArrayList<>(inherited);
        if (options.respectIgnore()) addIgnore(directory, rules, visitor, control, physicalPaths);
        try (var entries = Files.newDirectoryStream(directory)) {
            for (Path path : entries) {
                control.checkpoint();
                if (++visited[0] > 1_000_000) throw new IOException("File inventory limit reached; narrow the search scope");
                BasicFileAttributes attributes;
                try { attributes = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS); }
                catch (IOException unavailable) { issue(path, "Cannot inspect file", visitor); continue; }
                boolean isDirectory = attributes.isDirectory();
                if (!accepted(path, isDirectory, includes, excludes, rules, physicalPaths)) continue;
                if (isDirectory) walk(path, rules, includes, excludes, covered, physicalBuffers, control, visitor, visited, depth + 1, physicalPaths);
                else if (attributes.isRegularFile()) {
                    Path bufferPath = attributes.fileKey() == null ? null : physicalBuffers.get(attributes.fileKey());
                    if (bufferPath != null && Files.isSameFile(bufferPath, path)) continue;
                    String key = path.toRealPath().toUri().toASCIIString();
                    if (!covered.contains(key)) visitor.accept(file(path, key));
                }
            }
        } catch (IOException unavailable) { issue(directory, unavailable.getMessage(), visitor); }
    }
    private boolean accepted(Path path, boolean directory, List<FastIgnoreRule> includes,
                             List<FastIgnoreRule> excludes, List<Ignore> rules, Function<Path, Path> physicalPaths) {
        Path relative = root.relativize(path);
        for (Path name : relative) {
            if (name.toString().equals(".git")) return false;
            if (!options.hidden() && name.toString().startsWith(".")) return false;
        }
        String display = slash(relative);
        if (excludes.stream().anyMatch(rule -> rule.isMatch(display, directory))) return false;
        if (ignored(physicalPaths.apply(path), directory, rules)) return false;
        return directory || includes.isEmpty() || includes.stream().anyMatch(rule -> rule.isMatch(display, false));
    }
    private static boolean ignored(Path path, boolean directory, List<Ignore> rules) {
        for (int i = rules.size() - 1; i >= 0; i--) {
            Ignore ignore = rules.get(i);
            if (!path.startsWith(ignore.directory()) || path.equals(ignore.directory())) continue;
            var result = ignore.rules().isIgnored(slash(ignore.directory().relativize(path)), directory);
            if (result == IgnoreNode.MatchResult.IGNORED) return true;
            if (result == IgnoreNode.MatchResult.NOT_IGNORED) return false;
        }
        return false;
    }
    private void addIgnore(Path directory, List<Ignore> rules, ProjectSearch.SourceVisitor visitor,
                           ProjectSearch.Control control, Function<Path, Path> physicalPaths) throws IOException {
        Path file = directory.resolve(".gitignore");
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) return;
        control.checkpoint();
        try (var input = Files.newInputStream(file)) {
            byte[] bytes = input.readNBytes(65537);
            if (bytes.length > 65536) { issue(file, ".gitignore exceeds 64 KiB", visitor); return; }
            IgnoreNode node = new IgnoreNode();
            node.parse(new ByteArrayInputStream(bytes));
            rules.add(new Ignore(physicalPaths.apply(directory), node));
        } catch (IOException failure) { issue(file, "Cannot read .gitignore", visitor); }
    }
    private ProjectSearch.Source file(Path path, String key) {
        return new ProjectSearch.Source() {
            public String key() { return key; }
            public String label() { return JvmSearchSources.this.label(path); }
            public dev.tuink.editor.storage.DocumentResource resource() { return JvmDocumentStorage.resource(path); }
            public ProjectSearch.ReadVersion read(ProjectSearch.Control control, ProjectSearch.Lines lines) throws IOException {
                return SearchFileReader.read(path, options.encoding(), options.maximumFileMiB() * 1024L * 1024L, control, lines);
            }
        };
    }
    private String label(Path path) { return labels.apply(path); }
    private void issue(Path path, String message, ProjectSearch.SourceVisitor visitor) throws IOException {
        visitor.accept(new ProjectSearch.Source() {
            public String key() { return path.toUri().toASCIIString(); }
            public String label() { return JvmSearchSources.this.label(path); }
            public dev.tuink.editor.storage.DocumentResource resource() { return JvmDocumentStorage.resource(path); }
            public ProjectSearch.ReadVersion read(ProjectSearch.Control control, ProjectSearch.Lines lines) throws IOException {
                throw new IOException(message);
            }
        });
    }
    private static List<FastIgnoreRule> masks(String value) {
        ArrayList<FastIgnoreRule> result = new ArrayList<>();
        for (String mask : value.split(",")) {
            if (mask.isBlank()) continue;
            String pattern = mask.strip();
            FastIgnoreRule rule = new FastIgnoreRule(pattern);
            if (pattern.startsWith("!") || rule.isEmpty())
                throw new IllegalArgumentException("Invalid path mask: use positive patterns in In and Out");
            result.add(rule);
        }
        return List.copyOf(result);
    }
    private static String slash(Path path) {
        String result = path.toString();
        return path.getFileSystem().getSeparator().equals("\\") ? result.replace('\\', '/') : result;
    }
}
