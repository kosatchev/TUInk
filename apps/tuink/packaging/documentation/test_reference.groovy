import java.nio.file.Files

def resolve = resolveReference
String revision = '0123456789abcdef0123456789abcdef01234567'
File temporary = Files.createTempDirectory('tuink-documentation-reference-').toFile()
int fixtures = 0
try {
    File source = new File(temporary, 'export')
    assert source.mkdir()
    new File(source, 'gradle.properties').text = 'version=0.16.0\n'
    def noGit = { List<String> arguments ->
        throw new AssertionError("Exported sources must not borrow a parent repository: ${arguments}")
    }
    def rejects = { String label, String expected, Closure action ->
        try {
            action.call()
            throw new AssertionError("${label}: expected refusal")
        } catch (IllegalArgumentException failure) {
            assert failure.message.contains(expected) : "${label}: ${failure.message}"
        }
        fixtures++
    }

    rejects('version alone cannot identify exported sources', '-PdocumentationRef=') {
        resolve.call(source, null, noGit)
    }
    assert resolve.call(source, revision, noGit) == revision
    assert resolve.call(source, 'v0.16.0', noGit) == 'v0.16.0'
    fixtures += 2

    ['', 'main', '0123456', 'A' * 40, 'v1.2', 'v1.2.3/../main', 'v1.2.3#part'].each { ref ->
        rejects('invalid explicit provenance', 'full Git commit') {
            resolve.call(source, ref, noGit)
        }
    }

    File checkout = new File(temporary, 'checkout')
    assert new File(checkout, '.git').mkdirs()
    List<List<String>> calls = []
    def git = { List<String> arguments ->
        calls.add(arguments)
        arguments == ['rev-parse', '--show-toplevel'] ? checkout.absolutePath : revision
    }
    assert resolve.call(checkout, null, git) == revision
    assert calls == [['rev-parse', '--show-toplevel'], ['rev-parse', 'HEAD']]
    fixtures++
    assert resolve.call(checkout, 'v1.2.3', noGit) == 'v1.2.3'
    fixtures++

    rejects('unrelated Git root cannot supply provenance', 'Git root does not match') {
        resolve.call(checkout, null, { List<String> arguments -> temporary.absolutePath })
    }
    rejects('invalid detected HEAD is rejected', 'full Git commit') {
        resolve.call(checkout, null, { List<String> arguments ->
            arguments == ['rev-parse', '--show-toplevel'] ? checkout.absolutePath : 'HEAD'
        })
    }

    File worktree = new File(temporary, 'worktree')
    assert worktree.mkdir()
    new File(worktree, '.git').text = 'gitdir: /repository/.git/worktrees/documentation\n'
    assert resolve.call(worktree, null, { List<String> arguments ->
        arguments == ['rev-parse', '--show-toplevel'] ? worktree.absolutePath : revision
    }) == revision
    fixtures++
} finally {
    assert temporary.deleteDir()
}
println "Distribution documentation reference fixtures passed: ${fixtures}"
