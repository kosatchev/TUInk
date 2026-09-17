// Exercises the production checker supplied through GroovyShell's findProblems binding.
// These fixtures contain archive-relative paths; no repository or filesystem lookup is needed.
def checker = findProblems
int fixtures = 0

def bundle = { Map relativeMarkdown, Collection relativeFiles = [], String root = 'bundle' ->
    Map<String, String> markdown = relativeMarkdown.collectEntries { name, text ->
        [((root + '/' + name).toString()): text.toString()]
    }
    Set<String> paths = relativeFiles.collect { (root + '/' + it).toString() } as Set
    paths.addAll(markdown.keySet())
    [markdown: markdown, paths: paths, root: root]
}

def inspect = { String label, Map archive ->
    def problems = checker.call(archive.markdown, archive.paths, archive.root)
    assert problems instanceof List : "${label}: checker must return a list, got ${problems}"
    fixtures++
    problems.collect { it.toString() }
}

def clean = { String label, Map archive ->
    def problems = inspect(label, archive)
    assert problems.isEmpty() : "${label}: unexpected link failures: ${problems}"
}

def broken = { String label, Map archive, List locations ->
    def problems = inspect(label, archive)
    assert problems.size() == locations.size() :
        "${label}: expected ${locations.size()} failures, got ${problems}"
    locations.each { location ->
        String prefix = archive.root ? archive.root + '/' : ''
        String source = prefix + location[0] + ':' + location[1]
        assert problems.any { it.contains(source) } :
            "${label}: failure must identify ${source}, got ${problems}"
    }
}

// A nested documentation fixture: root README links resolve, but four nested links do not.
def shipped = [
    'README.md': '[Build](docs/building.md)\n[Architecture](docs/architecture-roadmap.md)\n'
        + '[Compatibility](docs/compatibility.md)\n',
    'docs/building.md': '[Engine probe](../guides/engine/README.md)\n'
        + '[UI regressions](../guides/ui/README.md)\n',
    'docs/architecture-roadmap.md': '[Android probe](../guides/engine/README.md)\n',
    'docs/compatibility.md': '[Engine probe](../guides/engine/README.md)\n'
]
broken('the four missing links behind the release README', bundle(shipped), [
    ['docs/building.md', 1], ['docs/building.md', 2],
    ['docs/architecture-roadmap.md', 1], ['docs/compatibility.md', 1]
])
def completed = shipped + [
    'guides/engine/README.md': '# External engine probe\n',
    'guides/ui/README.md': '# Terminal UI regressions\n'
]
clean('adding the two linked README files closes the bundle', bundle(completed))

def nested = [
    'README.md': '[Guide](docs/guide.md)\n[ADRs](docs/adr/)\n'
        + '![Demo](docs/assets/demo.gif)\n[License](LICENSE)\n',
    'docs/guide.md': '[Root](../README.md#overview)\n[ADR](adr/0001.md?view=plain#decision)\n',
    'docs/adr/0001.md': '[Guide](../guide.md)\n[Root](../../README.md)\n'
]
def nestedArchive = bundle(nested, ['docs/assets/demo.gif', 'LICENSE'])
assert !nestedArchive.paths.contains('bundle/docs/adr/')
assert !nestedArchive.paths.contains('bundle/docs/adr')
clean('nested parents, images, ordinary files and implicit directories', nestedArchive)
broken('missing image is checked as well as Markdown files', bundle([
    'README.md': '# Bundle\n![Missing](docs/assets/absent.png)\n'
]), [['README.md', 2]])

clean('external, fragment-only, query-only and empty destinations', bundle([
    'README.md': '[Web](https://example.invalid/not-bundled.md?q=1#anchor)\n'
        + '[HTTP](http://example.invalid/a.md)\n[Mail](mailto:maintainer@example.invalid)\n'
        + '![CDN](//example.invalid/image.png)\n[Here](#overview)\n'
        + '[Query](?download=1#overview)\n[Same document]()\n'
]))

clean('fenced, indented, inline and commented examples do not create links', bundle([
    'README.md': '# Examples\n\n```markdown\n[Not a link](missing-fence.md)\n```\n'
        + '\n~~~markdown\n![Not an image](missing-tilde.png)\n~~~\n'
        + '\n    [Indented example](missing-indent.md)\n'
        + '\n\t[Tab-indented example](missing-tab.md)\n'
        + '\n`[Inline example](missing-inline.md)`\n'
        + '``literal ` and [an example](missing-double.md)``\n'
        + '<!-- [Comment](missing-comment.md) -->\n'
        + '<!--\n[Multiline comment](missing-multiline.md)\n-->\n'
        + '\\[Escaped bracket](missing-escaped.md)\n'
]))
broken('an unmatched backtick does not hide a real link', bundle([
    'README.md': '`unclosed marker [Actual link](missing.md)\n'
]), [['README.md', 1]])
broken('escaped backticks are literal text around a real link', bundle([
    'README.md': '\\`[Actual link](missing.md)\\`\n'
]), [['README.md', 1]])
broken('line numbers survive ignored fenced blocks and comments', bundle([
    'README.md': '```text\n[Example](missing-example.md)\n```\n'
        + '<!-- [Comment](missing-comment.md) -->\n[Actual link](missing-real.md)\n'
]), [['README.md', 5]])

clean('angle destinations, spaces, percent escapes, literal plus and Unicode', bundle([
    'README.md': '[Angle](<docs/angle space.md>)\n'
        + '[Encoded space](docs/space%20name.md "Readable title")\n'
        + '[Encoded hash](docs/name%23part.md#chapter)\n'
        + '[Literal plus and Unicode](docs/a+b-кириллица-😀.md)\n'
        + '[Encoded Unicode](docs/%D0%96.md)\n'
        + '[Parameters](docs/space%20name.md?download=1#chapter)\n',
    'docs/angle space.md': '# Angle destination\n',
    'docs/space name.md': '# Space destination\n',
    'docs/name#part.md': '# Hash in the actual filename\n',
    'docs/a+b-кириллица-😀.md': '# Unicode and a literal plus\n',
    'docs/Ж.md': '# Percent-encoded Unicode\n'
]))
broken('plus is not form-urlencoded into a space', bundle([
    'README.md': '[A plus filename](docs/a+b.md)\n',
    'docs/a b.md': '# This is a different filename\n'
]), [['README.md', 1]])

clean('balanced and escaped parentheses belong to the destination', bundle([
    'README.md': '[Nested parentheses](docs/guide(v2).md "Guide title")\n'
        + '[Escaped parentheses](docs/guide\\(v2\\).md)\n'
        + '![Diagram](docs/assets/diagram(one).png)\n',
    'docs/guide(v2).md': '# Guide\n'
], ['docs/assets/diagram(one).png']))
broken('parentheses must not truncate a missing destination into an existing prefix', bundle([
    'README.md': '[Missing](docs/guide(v2).md)\n'
], ['docs/guide(v2']), [['README.md', 1]])

clean('reference links and their definition destinations', bundle([
    'README.md': '[Guide][Build Guide]\n![Diagram][picture]\n[Readme][]\n'
        + '\n[build guide]: docs/guide.md "Guide title"\n'
        + '[picture]: docs/assets/demo.png\n[readme]: README.md\n'
        + '[unused]: <docs/angle space.md> "Unused but valid"\n'
        + '[website]: https://example.invalid/not-bundled.md\n',
    'docs/guide.md': '# Guide\n',
    'docs/angle space.md': '# Angle\n'
], ['docs/assets/demo.png']))
broken('unused reference definition destinations are still verified', bundle([
    'README.md': '# References\n\n[unused]: docs/missing.md\n'
        + '[other-unused]: <docs/another missing.md> "Title"\n'
]), [['README.md', 3], ['README.md', 4]])
clean('reference-looking definitions inside examples are ignored', bundle([
    'README.md': '```markdown\n[unused]: missing-fence.md\n```\n'
        + '\n    [unused]: missing-indent.md\n'
        + '\n`[unused]: missing-inline.md`\n<!-- [unused]: missing-comment.md -->\n'
]))

def escaping = bundle([
    'README.md': '[Outside](../outside.md)\n'
        + '[Sibling prefix](../bundle-other/README.md)\n'
        + '[Encoded parent](%2e%2e/outside.md)\n'
        + '[Local file URI](file:///outside.md)\n'
        + '[Absolute file](/outside.md)\n'
])
escaping.paths.addAll(['outside.md', 'bundle-other/README.md', '/outside.md'])
broken('existing files outside the documentation root cannot satisfy links', escaping,
    [['README.md', 1], ['README.md', 2], ['README.md', 3], ['README.md', 4], ['README.md', 5]])

def withoutReadme = bundle(['docs/guide.md': '# No root README\n'])
def readmeProblems = inspect('a documentation root must contain README.md', withoutReadme)
assert !readmeProblems.isEmpty()
assert readmeProblems.any { it.contains('README.md') && it.contains(withoutReadme.root) } : readmeProblems

[
    'tuink-0.16.0',
    'tuink-0.16.0-linux-x86_64',
    'tuink-0.16.0-linux-x86_64-musl-pgo',
    'usr/share/doc/tuink'
].each { root ->
    clean("the same completed bundle under ${root}", bundle(completed, [], root))
    def withForeignDocument = bundle(['README.md': '# Valid bundle\n'], [], root)
    String other = root + '-other/README.md'
    withForeignDocument.markdown[other] = '[Foreign broken link](missing.md)\n'
    withForeignDocument.paths.add(other)
    clean("documents outside ${root} are ignored, including sibling prefixes", withForeignDocument)
}

Map<String, String> installedMarkdown = [
    'README.md': '[Guide](docs/guide.md)\n',
    'docs/guide.md': '[Parent README](../README.md)\n'
]
clean('installDist uses an empty root with relative FileTree addresses', [
    markdown: installedMarkdown, paths: installedMarkdown.keySet() as Set, root: ''
])
broken('an empty installDist root still rejects parent traversal', [
    markdown: ['README.md': '[Outside](../outside.md)\n'],
    paths: ['README.md', 'outside.md'] as Set,
    root: ''
], [['README.md', 1]])

Map<String, String> backslashMarkdown = [
    'bundle/README.md': '[Guide](docs/guide.md)\n',
    'bundle/docs\\guide.md': '# A distinct archive name, not docs/guide.md\n'
]
broken('a literal backslash in an archive key is not a slash alias', [
    markdown: backslashMarkdown, paths: backslashMarkdown.keySet() as Set, root: 'bundle'
], [['README.md', 1]])

println "Distribution documentation link fixtures passed: ${fixtures}"
