# Terminal UI regressions

Run `./gradlew :app:uiRegressionTest` on Linux with Python 3.10+ and Java 25.
This separate gate builds the JVM install layout and exercises the real
application through a POSIX pseudo-terminal. It is explicitly run by Linux CI;
ordinary `check` remains independent of PTY availability.

`./gradlew :app:uiHarnessTest` only validates the screen parser and process
cleanup. It does not start Java. The parser observes text/cursor geometry,
understands scroll regions and ANSI S/T scroll hints, and rejects unsupported
output. Its Unicode checks cover combining marks and double-width cells; it is
not intended to emulate every xterm feature or verify terminal colors.

The scenarios protect the following user behavior:

| Scenario | Invariant |
|---|---|
| `reading` | Rejected edits preserve manual horizontal/vertical viewing position; actual cursor navigation resumes following |
| `focus` | Opening Find ends document selection capture; a later drag cannot steal focus |
| `field` | Copy and a menu roundtrip preserve the long field's viewport in color and monochrome |
| `resize` | Wrapped text retains its textual anchor when terminal width/height change |
| `tabs` | Save, Copy, and returning to a tab preserve its independent viewport |
| `history` | Undo/Redo through the real keymap preserve Unicode text and grouping |
| `recovery` | After autosave and a forced crash, a second process restores and saves unsaved edits |

For an already built install layout, select scenarios directly:

```sh
python3 -B verification/ui/run.py --java "$JAVA_HOME/bin/java" focus resize
```

`--classpath` accepts installed JVM JARs, defaulting to
`apps/tuink/launcher/build/install/tuink/lib/*`; `--output-root` selects the log directory.
There is no hard-coded SDK or temporary path. Each run creates isolated homes,
settings, fixtures, raw output logs, final screen snapshots, and a JSON result
under `build/ui-regression/run-*`. The classpath is hashed before and after the
run to reject concurrent rebuilds. Do not rebuild the distribution while a gate
is running. Every process has a bounded startup, scenario, and exit deadline,
and cleanup reaps it on both success and failure. Successful scenarios require
a normal Ctrl+Q exit; the first recovery process instead checks its expected
SIGKILL termination after confirming the unsaved draft reached recovery storage.

When adding a regression, assert an externally visible invariant and file bytes
where relevant. Add parser self-tests before relying on new escape sequences.
