import groovy.io.FileType

// Check the production staging outputs, including the complete upstream notice
// trees. A developer document or unrelated runtime notice must not leak back in.
Set<String> common = ['README.md', 'LICENSE', 'THIRD_PARTY_NOTICES.md', 'SECURITY.md',
    'docs/assets/tuink-menus.gif', 'docs/assets/tuink-tabs.gif'] as Set
Map<String, List<String>> requiredNotices = [
    jvm: [],
    glibc: ['oracle-graalvm-25.0.4'],
    musl: ['oracle-graalvm-25.0.4', 'musl-1.2.6', 'zlib-1.3.2']
]
assert bundles.keySet() == requiredNotices.keySet()
bundles.each { String target, File directory ->
    Set<String> expected = new HashSet<>(common)
    requiredNotices[target].each { bundle ->
        new File(sourceRoot, "licenses/${bundle}").eachFileRecurse(FileType.FILES) { file ->
            expected.add(sourceRoot.toPath().relativize(file.toPath()).toString().replace(File.separator, '/'))
        }
    }
    Set<String> actual = [] as Set
    directory.eachFileRecurse(FileType.FILES) { file ->
        actual.add(directory.toPath().relativize(file.toPath()).toString().replace(File.separator, '/'))
    }
    assert actual == expected : "${target}: missing ${expected - actual}; unexpected ${actual - expected}"
    (expected - ['README.md', 'THIRD_PARTY_NOTICES.md']).each { name ->
        assert new File(directory, name).bytes == new File(sourceRoot, name).bytes :
            "${target}: changed source bytes in ${name}"
    }
    String readme = new File(directory, 'README.md').getText('UTF-8')
    assert readme.contains("https://github.com/kosatchev/tuink/blob/${documentationRef}/docs/building.md")
    assert readme.contains('](LICENSE)') && readme.contains('](THIRD_PARTY_NOTICES.md)')
    assert readme.contains('](docs/assets/tuink-menus.gif)')
    assert readme.contains('](SECURITY.md)')
    String notices = new File(directory, 'THIRD_PARTY_NOTICES.md').getText('UTF-8')
    ['oracle-graalvm-25.0.4', 'musl-1.2.6', 'zlib-1.3.2'].each { bundle ->
        String destination = "licenses/${bundle}/README.md"
        String rendered = requiredNotices[target].contains(bundle) ? destination
            : "https://github.com/kosatchev/tuink/blob/${documentationRef}/${destination}"
        assert notices.contains("](${rendered})") : "${target}: incorrect notice destination for ${bundle}"
    }
}
println('Distribution documentation: all three payloads, pinned links and exact notice bytes passed.')
