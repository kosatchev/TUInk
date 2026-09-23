# Contributing to TUInk

Thanks for helping improve TUInk. This guide defines the repository conventions used for code, tests, documentation, and commits.

Before changing build or packaging behavior, read [Build and native targets](docs/building.md) and [Release archives and Debian packaging](docs/packaging.md). Changes involving persisted data or installation ownership must follow the [Compatibility policy](docs/compatibility.md).

## Development setup

Development requires a JDK 17 toolchain. Set `JAVA_HOME` to JDK 17 and use the checked-in Gradle wrapper so local and CI builds use the same Gradle version. Native-image work additionally requires the exact GraalVM Community 25.2.4 / Java 25 distribution, selected separately with an absolute `GRAALVM_HOME`; keep `JAVA_HOME` on JDK 17. See [Native toolchain](docs/building.md#native-toolchain) for the pinned Linux x86_64 download and checksum, or the [setup and transfer guide in Russian](docs/setup-ru.md) for a new computer.

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

Respect module boundaries and keep dependencies directed toward stable abstractions. Terminal libraries, ANSI sequences, and JLine-specific types belong in terminal integration code; they must not leak into core editor or rendering models.

Prefer small, typed state transitions over boolean flag combinations. Keep parsing, validation, state mutation, rendering, and I/O separable enough to test independently.

## Compatibility and persistence

TUInk targets only the current release contract. Do not introduce migrations, historical readers, deprecated aliases, or old-path fallbacks. See the [Compatibility policy](docs/compatibility.md).

Every persisted schema change must update:

- the format marker and strict reader validation;
- complete current-schema serialization;
- rejection and data-preservation tests;
- the compatibility document and release notes when user action is required.

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
