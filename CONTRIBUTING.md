# Contributing to TUInk

Thanks for helping improve TUInk. This guide defines the repository conventions used for code, tests, documentation, and commits.

Before changing build or packaging behavior, read [Build and native targets](docs/building.md) and [Release archives and Debian packaging](docs/packaging.md). Changes involving persisted data or installation ownership must follow the [Compatibility policy](docs/compatibility.md).

## Development setup

JVM development uses Java 25 from any vendor. The checked-in Gradle wrapper
can run on a compatible JDK, such as 17, 21 or 25, and finds or downloads a
Java 25 toolchain for compilation, tests and application runs. No specific
Java patch version or vendor is required for JVM builds.

Native releases and optional PGO use the pinned Oracle GraalVM
25.0.4+7.1 SDK with Java 25. Set both `JAVA_HOME` and `GRAALVM_HOME` to its
absolute path when building native binaries. See
[Native toolchain](docs/building.md#native-toolchain) for the exact version,
Linux x86_64 download, and checksum, or the
[setup and transfer guide in Russian](docs/setup-ru.md) for a new computer.
The optional [PGO workflow](docs/building.md#pgo) uses the same SDK and
`verifyNativeToolchain` preflight with a separately trained profile.

```bash
./gradlew check
./gradlew run
```

## Commit messages

Use Conventional Commits in this form:

```text
type(scope): description
```

Allowed types are `feat`, `fix`, `refactor`, `perf`, `test`, `docs`, `build`, and `chore`.

Common scopes include `app`, `core`, `editor`, `highlight`, `open-file`, `packaging`, `release`, `render`, `search`, `storage`, `tui`, `viewport`, and `wrap`. Use another scope only when it names a clear subsystem. Omit the scope for repository-wide changes.

Write the subject in English, in the imperative present tense, with a lowercase description and no trailing period. Prefer at most 72 characters. Scopes use lowercase kebab-case. Use the body to explain motivation and non-obvious tradeoffs. Mark incompatible public changes with a `BREAKING CHANGE:` footer.

Examples:

```text
feat(editor): add multi-cursor selection
fix(storage): preserve incompatible recovery snapshots
refactor(tui): replace modal flags with typed state
test(packaging): cover markerless installation refusal
docs: document current storage format
chore(release): prepare v0.11.0
```

Keep each commit atomic. Include the tests and documentation required by its behavior change in the same commit. Do not mix unrelated refactoring, generated files, IDE state, local profiles, logs, or secrets. Squash temporary fixup commits before sharing a branch, and coordinate with collaborators before rewriting published history.

## Architecture

Use the [repository layout](README.md#repository-layout) to place new code:

- `apps/<name>/` owns an application's frontend, launchers, product-specific
  packaging, and sample documents. TUInk's `terminal-rendering` handles text
  geometry and render plans; `terminal-ui` binds them to windows and commands.
- `libs/editor/` owns the shared editor API, engine, session, and concrete
  adapters. Keep terminal and filesystem dependencies out of the portable
  API, engine, and session modules.
- `libs/tuiframe/` owns reusable terminal runtime, widgets and the filesystem
  explorer without editor or application policy. `tuiframe-explorer` owns file
  operations and history; TUInk adapts receipts to document bindings.
  `libs/unicode-text/` owns the independent text primitive.
- Unit tests belong in their module's `src/test`. Shared fixtures, complete
  application scenarios, performance tools, and external compatibility probes
  belong under `verification/` in the corresponding named directory.
- Shared build logic belongs in `gradle/`; documentation belongs in `docs/`.
  Generated files belong in `build/`, and local research and diagnostics in
  ignored `.local/`.

Start new functionality in a Java package. Introduce a Gradle module when it
needs independent reuse, dependency isolation, or a different runtime baseline.
Physical paths are declared in `settings.gradle`; preserve existing Gradle
project paths, Java packages, and artifact coordinates during directory moves.

Respect module boundaries and keep dependencies directed toward stable abstractions. Terminal libraries, ANSI sequences, and JLine-specific types belong in terminal integration code; they must not leak into core editor or rendering models.

Prefer small, typed state transitions over boolean flag combinations. Keep parsing, validation, state mutation, rendering, and I/O separable enough to test independently.

## Compatibility and persistence

TUInk targets the current release contract. The documented recovery reader for
exact snapshot schemas 5 through 8 is an explicit exception that protects
unfinished drafts; writers use only the current format. Outside that exception,
do not introduce migrations, historical readers, deprecated aliases, or old-path
fallbacks. See the [Compatibility policy](docs/compatibility.md).

Every persisted schema change must update:

- the format marker for incompatible changes, and strict reader validation;
- complete required-field serialization and explicit defaults for optional fields;
- rejection and data-preservation tests;
- the compatibility document and release notes when user action is required.

An additive optional preference may keep the current format when its absence
has a documented default. Required fields and unknown-field rejection remain
strict; this does not permit reading historical formats or migrating profiles.

Never silently reinterpret incompatible data. Validate paths, reject unsafe ownership assumptions, and preserve user files unless deletion is explicit.

## Tests

Add tests at the narrowest useful level and include a regression test for every bug fix. Tests should be deterministic, independent of execution order, and free of the developer's home directory, locale, terminal, and clock unless those inputs are intentionally controlled.

Run the focused test while developing, then the full suite before committing:

```bash
./gradlew check
```

`check` runs all module tests and the architecture/dependency-boundary gates.
For benchmark-harness changes, also run `./gradlew :app:perfHarnessTest` on a
POSIX system with Python 3.10+ available as `python3`. CI runs this self-test
explicitly, and native benchmark/release tasks retain it as a prerequisite.
For PGO training changes, also run `./gradlew :app:pgoHarnessTest`; this
self-test needs only Python and runs in CI without a native build.
When intentionally changing dependencies, also update and review the lockfiles
and verification metadata as described in [Build and native targets](docs/building.md#dependency-integrity).

Packaging changes should also exercise the relevant archive, installer, or Debian-package checks documented in [docs/packaging.md](docs/packaging.md).

## Documentation

Update documentation in the same commit when behavior, commands, configuration, key bindings, storage formats, build prerequisites, or packaging procedures change. Describe the current supported behavior and call out destructive actions explicitly.

## Packaging and releases

Release artifacts must be produced by the repository scripts and must preserve the ownership and containment guarantees documented in the compatibility policy. Do not hand-edit generated archives or package contents.

Before changing shell scripts, validate their syntax:

```bash
bash -n apps/tuink/packaging/archive/install.sh
bash -n apps/tuink/packaging/archive/uninstall.sh
```

Use the release and packaging commands documented in [docs/packaging.md](docs/packaging.md), including the complete target matrix when preparing a release.

## Before committing

At minimum:

```bash
./gradlew check
git diff --check
```

Also confirm that the commit is focused, its message follows the repository style, user-visible changes are documented, compatibility-sensitive changes fail safely, and no local or generated artifacts are staged accidentally.
