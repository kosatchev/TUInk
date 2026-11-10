# Terminal UI regressions

Run `./gradlew :app:uiRegressionTest` on Linux with Python 3.10+ and Java 25.
This separate gate builds the JVM install layout and exercises the real
application through a POSIX pseudo-terminal. It is explicitly run by Linux CI;
ordinary `check` remains independent of PTY availability.

`./gradlew :app:uiHarnessTest` validates the screen parser, delayed field
observations and process cleanup. It does not start Java. The parser observes
text/cursor geometry, understands scroll regions and ANSI S/T scroll hints,
and rejects unsupported output. Its Unicode checks cover combining marks and
double-width cells; it is not intended to emulate every xterm feature or verify
terminal colors.

The scenarios protect the following user behavior:

| Scenario | Invariant |
|---|---|
| `search-interface` | The compact picker and wide direct mode buttons preserve semantics; regex errors, empty matches and no results are visible; full result locations and explained file filters work after resizing |
| `tool-palettes` | Files and Search stay open together; collapse preserves results; captured header/divider drags reorder and resize; the keyboard menu maximizes/restores; a fresh process restores saved layout |
| `reading` | Rejected edits preserve manual horizontal/vertical viewing position; actual cursor navigation resumes following |
| `focus` | Opening Find ends document selection capture; a later drag cannot steal focus |
| `field` | Copy and a menu roundtrip preserve the long field's viewport in color and monochrome |
| `resize` | Wrapped text retains its textual anchor when terminal width/height change |
| `large-wrap` | A 2 MB document with 400,000 short lines survives wrapping and repeated resizes on a 192 MiB JVM heap |
| `tabs` | Save, Copy, and returning to a tab preserve its independent viewport |
| `folder-explorer` | A CLI directory opens the left tree; Ctrl+E and Right/Enter open its child file, Ctrl+B and narrow resize preserve the expanded tree, and Escape returns to editing before Save writes the exact expected bytes |
| `project-search` | The menu and Ctrl+Shift+F search closed files and live Source buffers, respect ignore rules, include hidden files, open the exact match, and preserve query/results across Folders and sidebar toggles; only an explicit Save changes the selected file |
| `regex-search` | Strict/Wildcard/Regex use the common matcher; invalid regex leaves the document intact, and a zero-width anchor navigates to an empty line |
| `picker-navigation` | After moving to the parent with `..`, single clicks select, disclosure clicks and Left/Right expand or collapse, and a label double-click or Enter makes the directory the root without editing any files |
| `history` | Undo/Redo through the real keymap preserve Unicode text and grouping |
| `grapheme-edit-caret` | Typing after replacing part of a combining cluster or joining lines continues after the complete grapheme |
| `selection-autoscroll` | A stationary drag at either viewport edge keeps scrolling; release and opening Find stop the timer |
| `long-copy-name` | Saving a read-only file with a 255-byte name generates a fitting copy name, skips an occupied candidate and preserves both existing files |
| `navigation` | Ctrl+Home/End and Shift combinations move or select the correct Unicode range, which can be replaced and undone |
| `fragmented-keys` | Split ESC/CSI/SS3 arrows preserve document bytes and search focus across delayed delivery; standalone Escape still closes search |
| `quit-confirmation` | First Ctrl+Q asks; Esc cancels, Y saves, and N or another Ctrl+Q discards without writing; N works from Source and Preview |
| `gutter-idle` | Deferred change markers appear after rapid edits and clear after Undo without another input event |
| `paste` | Terminal paste keeps literal text and Unicode content in a separate Undo step |
| `settings` | CLI overrides apply to the current session while explicit menu changes preserve saved preferences and dotfile symlinks |
| `settings-conflict` | Ordinary menu changes persist; editing the app's own settings document and changing a menu preference retains the exact document bytes through Save and normal quit, with conflict feedback, for regular settings files and dotfile symlinks |
| `recovery` | After a forced crash, safe cancellation preserves drafts across normal exits and releases them for restoration by another instance while the first remains open |
| `recovery-format5` | A v0.13.0 draft is offered, postponed and restored without rewriting its files; later autosave uses format 8, Save preserves BOM/CRLF, and ambiguous old filenames require Save As |
| `recovery-memory` | Four valid 10 MiB drafts can be offered and postponed on a 64 MiB heap; failed restoration preserves every snapshot and the explicitly opened document |
| `linux-keys` | Linux console F1–F5 open their menus and Shift+Tab moves search focus backwards without inserting terminal sequence bytes into the document |
| `enhanced-text` | Shift-only CSI-u and modifyOtherKeys enter punctuation, Cyrillic and supplementary Unicode in the document and Find; command modifiers keep their shortcut behavior |
| `long-unicode-edit` | An accepted 11 MiB Unicode line supports editing, subsequent navigation, wildcard search and undo on a 192 MiB heap |
| `save-as-workspace` | Save As preserves a file owned by another tab, keeps the draft editable and accepts a different filename |
| `settings-memory` | Growth to a 2 GiB settings file produces a conflict on exit and defaults on the next launch, preserving the config on a 64 MiB heap |
| `syntax-memory` | Dense 3 MiB and 10 MiB CSV lines open, edit/undo, navigate, resize and wrap on 64 MiB and 192 MiB heaps |
| `unix-path-recovery` | A non-UTF-8 Unix filename opens through the picker, saves, and retains its identity through crash recovery beside a colliding display name |
| `literal-indentation` | Enter inside a Python multiline string, Java block comment or YAML block scalar copies whitespace without syntax-based indentation or list continuation; Undo restores the exact content |
| `field-memory` | A 2 MiB document can be copied into Find, Replace and Save As and navigated on a 128 MiB heap; cancelling the fields preserves the document bytes |
| `document-caption` | Long paths and filenames retain the extension and dirty marker in the header and tabs across resizes; the visible close button closes that tab |
| `save-name-suggestion` | An unnamed draft's proposed filename skips names reserved by other tabs, existing files and dangling symlinks |
| `replacement-memory` | A replacement expanding 500,000 matches to 32 MB is rejected on a 64 MiB heap; the editor remains usable, and Undo and Save preserve the original document |
| `paste-memory` | A 1 MiB multiline paste is rejected on a 32 MiB heap before replacing the selected draft; Save and Undo remain usable |
| `recovery-lock-cleanup` | Clean launches create no entry locks, and closing the last editor reclaims locks from a real background snapshot |
| `xdg-profile` | A configuration directory outside the home holds settings and recovery without touching the default profile |
| `cli-path-identity` | Ambiguous native argv filenames are rejected before startup; ASCII file URIs open and save the distinct raw-byte and literal `�` files |
| `terminal-diagnostics` | An unknown `TERM` containing OSC, line breaks and bidi controls produces a visible escaped JLine warning, then the editor starts and exits normally |
| `terminal-attributes` | At 9600 and 38400 baud, normal exit restores every measured termios field in default and `--no-alt-screen --no-mouse` modes; external SIGINT also exits normally, restores the terminal and retains the unsaved draft for recovery |
| `startup-dependencies` | Missing `tty` or `stty` causes a bounded startup error without ANSI output or changed termios, with colored and uncolored dumb-terminal fallbacks |
| `save-as-retarget` | A retargeted opening symlink still conflicts on Save; explicitly choosing its new target requires confirmation and saves only that file |
| `legacy-encoding` | An unencodable emoji keeps CP1251 bytes intact; safe cancellation and explicit UTF-8 Save As preserve CRLF, and format Undo/Redo retain the text and saved bytes |
| `document-format` | The footer opens Format; all seven output encodings save exact BOM/content bytes, Apply waits for Save, Undo restores mixed endings, Redo returns to the savepoint, and a fresh process explicitly decodes every format |
| `format-errors` | An unmappable emoji leaves the document and file unchanged with an actionable error; cancelling a changed format draft also works after shrinking the terminal |
| `format-reopen` | CP866 can be re-read in the existing tab; cancelling a dirty Reopen retains edits, explicit discard resets history, and a failed strict decoder retains the previous document |
| `field-edit-caret` | Deleting an emoji separator keeps subsequent input after the newly joined grapheme in Find and Save As |
| `field-memory-growth` | Dense Unicode field growth on a 128 MiB heap refuses a whole paste before exhaustion; editing and saving the document remain usable |
| `identical-replace-budget` | A single identical replacement exceeding its wildcard budget preserves the caret, selection and document |

For an already built install layout, select scenarios directly:

```sh
python3 -B verification/ui/run.py --java "$JAVA_HOME/bin/java" focus resize
```

Unsupported settings also have a focused JVM/native gate:

```sh
python3 -B verification/ui/unsupported_settings.py --java "$JAVA_HOME/bin/java"
python3 -B verification/ui/unsupported_settings.py --binary /absolute/path/to/tuink
```

It uses private homes to exercise Fix, Delete, default Later, temporary Preview
preferences, CLI overrides, external-change conflicts, whole-file defaults and
settings-before-recovery ordering. It verifies settings and document bytes and
keeps queued recovery copies through Ctrl+Q. `--output-root` selects its logs;
neither invocation uses the user's settings or desktop clipboard.

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

The terminal-attributes scenario measures the real PTY settings before child
execution, retains a slave descriptor until after the child exits, and compares
all flags, control characters and input/output speeds. It writes the measured
values to `terminal-attributes.json` in each case directory. The test does not
substitute assumed terminal defaults or ignore baud bits.
One case supplies `-Dorg.jline.terminal.provider=jni` to verify that a JVM
property cannot bypass the application's Linux provider choice and lose baud settings.
The startup-dependencies scenario supplies an isolated child-only `PATH` with
one required command omitted, sets `COLORTERM=truecolor`, and tests both values
of `org.jline.terminal.dumb.color`. It retains the PTY through the failed startup
and requires an error exit within five seconds.

When adding a regression, assert an externally visible invariant and file bytes
where relevant. Add parser self-tests before relying on new escape sequences.
Wait for a field's labelled row and expected visible content before taking its
coordinates or checking a newly entered value. Text elsewhere on the screen can
belong to the document or an earlier control. Keep intentional timing intervals
in fragmented-key and stability checks tied to the behavior those scenarios test.
