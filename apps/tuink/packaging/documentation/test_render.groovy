// Pure fixtures for the production renderer supplied through the renderDocumentation binding.
// All paths and contents are synthetic; these tests require no repository or filesystem lookup.
def render = renderDocumentation
String revision = '0123456789abcdef0123456789abcdef01234567'
String remote = "https://github.com/kosatchev/tuink/blob/${revision}/"
String tree = "https://github.com/kosatchev/tuink/tree/${revision}/"
int fixtures = 0

def expect = { String label, String original, String source, Map<String, Boolean> omitted, String expected ->
    String actual = render.call(original, source, omitted, revision)
    assert actual == expected : "${label}: expected ${expected.inspect()}, got ${actual.inspect()}"
    assert render.call(actual, source, omitted, revision) == actual : "${label}: rendering must be idempotent"
    fixtures++
}

expect('only known omitted paths are remapped',
    '[Dev](docs/building.md)\n[Keep](docs/usage.md)\n[License](LICENSE)\n[Unknown](missing.md)\n',
    'README.md', ['docs/building.md': false],
    "[Dev](${remote}docs/building.md)\n[Keep](docs/usage.md)\n[License](LICENSE)\n[Unknown](missing.md)\n")

expect('directory metadata selects tree without guessing by filename',
    '[Sources](verification/android/)\n[Dot directory](tools/v1.2/)\n[File without extension](benchmarks/NOTICE)\n',
    'README.md', ['verification/android': true, 'tools/v1.2': true, 'benchmarks/NOTICE': false],
    "[Sources](${tree}verification/android)\n[Dot directory](${tree}tools/v1.2)\n[File without extension](${remote}benchmarks/NOTICE)\n")

expect('every document resolves from its own parent',
    '[Probe](../verification/android/README.md)\n[Guide](./building.md#native)\n[Root](../README.md)\n',
    'docs/usage.md', ['verification/android/README.md': false, 'docs/building.md': false],
    "[Probe](${remote}verification/android/README.md)\n[Guide](${remote}docs/building.md#native)\n[Root](../README.md)\n")

expect('normalization and percent-decoded addresses select omitted paths',
    '[Probe](../%76erification/./other/../android/README.md?plain=1#result)\n',
    'docs/usage.md', ['verification/android/README.md': false],
    "[Probe](${remote}verification/android/README.md?plain=1#result)\n")

expect('URI paths preserve literal plus and encode Unicode by UTF-8',
    '[Unicode](<docs/a+b Ж😀.md?x=%2B+value#part>)\n',
    'README.md', ['docs/a+b Ж😀.md': false],
    "[Unicode](<${remote}docs/a%2Bb%20%D0%96%F0%9F%98%80.md?x=%2B+value#part>)\n")

expect('percent encoding is decoded once before encoding the URL path',
    '[Encoded](docs/a%20b%23part%3Fname%25.md#section)\n[Plus](docs/a+b.md)\n',
    'README.md', ['docs/a b#part?name%.md': false, 'docs/a+b.md': false, 'docs/a b.md': false],
    "[Encoded](${remote}docs/a%20b%23part%3Fname%25.md#section)\n[Plus](${remote}docs/a%2Bb.md)\n")

expect('bare balanced and escaped destinations retain optional titles',
    '[Guide](docs/guide(v2).md "Title (kept)")\n[Escaped](docs/guide\\(v2\\).md \'Title\')\n',
    'README.md', ['docs/guide(v2).md': false],
    "[Guide](${remote}docs/guide%28v2%29.md \"Title (kept)\")\n[Escaped](${remote}docs/guide%28v2%29.md 'Title')\n")

expect('angle destinations retain angle brackets and title bytes',
    '[Guide](<docs/space name.md>   "Readable title")\n',
    'README.md', ['docs/space name.md': false],
    "[Guide](<${remote}docs/space%20name.md>   \"Readable title\")\n")

expect('reference definitions are remapped including unused ones',
    '[Guide][build]\n![Picture][image]\n\n[build]: docs/building.md "Build"\n'
        + '[image]: <docs/assets/image one.png>\n[unused]: verification/android/README.md\n',
    'README.md', ['docs/building.md': false, 'docs/assets/image one.png': false,
                  'verification/android/README.md': false],
    "[Guide][build]\n![Picture][image]\n\n[build]: ${remote}docs/building.md \"Build\"\n"
        + "[image]: <${remote}docs/assets/image%20one.png>\n[unused]: ${remote}verification/android/README.md\n")

expect('reference definitions may put their destination on the next line',
    '[Guide][build]\n[build]:\n  docs/building.md "Build"\n',
    'README.md', ['docs/building.md': false],
    "[Guide][build]\n[build]:\n  ${remote}docs/building.md \"Build\"\n")

expect('nested image and link destinations are replaced in source order',
    '[![Image](docs/assets/image.png)](verification/android/README.md)\n',
    'README.md', ['docs/assets/image.png': false, 'verification/android/README.md': false],
    "[![Image](${remote}docs/assets/image.png)](${remote}verification/android/README.md)\n")

String examples = ('```markdown\n[Example](docs/building.md)\n```\n'
    + '~~~\n[Example](docs/building.md)\n~~~\n'
    + '    [Example](docs/building.md)\n\t[Example](docs/building.md)\n'
    + '`[Example](docs/building.md)` and ``[Example](docs/building.md)``\n'
    + '<!-- [Example](docs/building.md) -->\n<!--\n[Example](docs/building.md)\n-->\n'
    + '\\[Escaped](docs/building.md)\n')
expect('code, comments and escaped brackets are untouched', examples,
    'README.md', ['docs/building.md': false], examples)

expect('unmatched and escaped backticks do not hide actual links',
    '`unclosed [Actual](docs/building.md)\n\n\\`[Actual](docs/building.md)\\`\n',
    'README.md', ['docs/building.md': false],
    "`unclosed [Actual](${remote}docs/building.md)\n\n\\`[Actual](${remote}docs/building.md)\\`\n")

String invalid = ('[Outside](../docs/building.md)\n[Absolute](/docs/building.md)\n'
    + '[File](file:///docs/building.md)\n[Drive](C:/docs/building.md)\n'
    + '[Backslash](docs%5Cbuilding.md)\n[Bad escape](docs/%ZZ.md)\n'
    + '[Invalid UTF-8](docs/%C0%AE.md)\n[Encoded absolute](%2Fdocs/building.md)\n'
    + '[Unknown](docs/missing.md)\n')
expect('invalid or unclassified missing targets remain for the validator', invalid,
    'README.md', ['../docs/building.md': false, '/docs/building.md': false,
                  'docs/building.md': false, 'docs\\building.md': false], invalid)

String external = ('[Web](https://example.invalid/docs/building.md?x=1#part)\n'
    + '![CDN](//example.invalid/docs/building.md)\n[Mail](mailto:docs/building.md)\n'
    + '[Here](#docs/building.md)\n[Query](?docs/building.md)\n[Empty]()\n')
expect('external and document-local destinations are untouched', external,
    'README.md', ['docs/building.md': false], external)

expect('Markdown escapes and entities identify the repository filename',
    '[Punctuation](docs/name\\!.md)\n[Entity](docs/a&amp;b.md?raw=1#part)\n',
    'README.md', ['docs/name!.md': false, 'docs/a&b.md': false],
    "[Punctuation](${remote}docs/name%21.md)\n[Entity](${remote}docs/a%26b.md?raw=1#part)\n")

expect('literal backslashes in map keys cannot alias POSIX separators',
    '[Separate](docs/building.md)\n', 'README.md', ['docs\\building.md': false],
    '[Separate](docs/building.md)\n')

expect('CRLF, reference definitions and untouched Unicode retain their bytes',
    'Привет 😀\r\n[Guide](docs/building.md)\r\n[unused]: docs/building.md "Title"\r\n',
    'README.md', ['docs/building.md': false],
    "Привет 😀\r\n[Guide](${remote}docs/building.md)\r\n[unused]: ${remote}docs/building.md \"Title\"\r\n")

expect('legacy CR newlines retain their bytes', '[Guide](docs/building.md)\rNext\r',
    'README.md', ['docs/building.md': false], "[Guide](${remote}docs/building.md)\rNext\r")

String tag = 'v1.2.3'
assert render.call('[Guide](docs/building.md)', 'README.md', ['docs/building.md': false], tag) ==
    "[Guide](https://github.com/kosatchev/tuink/blob/${tag}/docs/building.md)"
fixtures++

['main', '0123456', 'A' * 40, 'v1.2', 'v1.2.3/../main', 'v1.2.3#part'].each { ref ->
    boolean rejected = false
    try {
        render.call('[Guide](docs/building.md)', 'README.md', ['docs/building.md': false], ref)
    } catch (IllegalArgumentException expected) {
        rejected = true
    }
    assert rejected : "Invalid documentation reference was accepted: ${ref}"
}
fixtures++

println "Distribution documentation render fixtures passed: ${fixtures}"
