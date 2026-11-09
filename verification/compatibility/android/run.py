#!/usr/bin/env python3
"""Compile an external JAR consumer, run it on JVM, and process it with D8/R8.

This checks the headless engine without terminal, JGit or FFM adapters.
The script uses installed tools; it does not download an SDK or start a device.
"""

import argparse
import os
from pathlib import Path
import subprocess
import tempfile
import zipfile


def run(arguments):
    subprocess.run([str(argument) for argument in arguments], check=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--jdk", type=Path, required=True)
    parser.add_argument("--runtime-jdk", type=Path, help="JDK for the JVM probe (for example Java 17)")
    parser.add_argument("--sdk", type=Path, required=True)
    parser.add_argument("--build-tools", required=True)
    parser.add_argument("--compile-api", required=True)
    parser.add_argument("--min-api", type=int, required=True)
    parser.add_argument("--output", type=Path)
    parser.add_argument("--markdown", action="store_true", help="Include portable Markdown, math and Mermaid providers")
    args = parser.parse_args()
    root = next(parent for parent in Path(__file__).resolve().parents
                if (parent / 'settings.gradle').is_file() and (parent / 'gradlew').is_file())
    version = next(line.split("=", 1)[1] for line in (root / "gradle.properties").read_text().splitlines()
                   if line.startswith("version="))
    modules = {
        'editor-contract': 'libs/editor/api',
        'unicode-text': 'libs/unicode-text',
        'editor-search-text': 'libs/editor/search-text',
        'core': 'libs/editor/engine',
        'editor-session': 'libs/editor/session',
        'highlight-regex': 'libs/editor/highlight-regex',
    }
    jars = [root / directory / "build/libs" / f"{module}-{version}.jar"
            for module, directory in modules.items()]
    if args.markdown:
        exported = root / 'build/compatibility/markdown-runtime-classpath.txt'
        if not exported.is_file():
            parser.error('Run ./gradlew writeMarkdownRuntimeClasspath first')
        jars = list(dict.fromkeys([*jars, *(Path(line) for line in exported.read_text().splitlines() if line)]))
    android = args.sdk / "platforms" / f"android-{args.compile_api}" / "android.jar"
    dexer = args.sdk / "build-tools" / args.build_tools / "lib/d8.jar"
    java = args.jdk / "bin/java"
    javac = args.jdk / "bin/javac"
    runtime_java = (args.runtime_jdk or args.jdk) / "bin/java"
    for path in [*jars, android, dexer, java, javac, runtime_java]:
        if not path.is_file():
            parser.error(f"Missing input/tool: {path}; build the six shared engine JARs first")

    output = (args.output or root / "build/compatibility/android").resolve()
    output.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="portable-probe-", dir=output) as workdir:
        work = Path(workdir)
        classes = work / "classes"
        classes.mkdir()
        probes = [root / "verification/compatibility/android/PortableEngineProbe.java",
                  root / "verification/fixtures/java/dev/tuink/verification/MemoryDocumentStorage.java"]
        if args.markdown:
            probes.append(root / "verification/compatibility/jvm/PortableMarkdownApiProbe.java")
        run([javac, "--release", "17", "-cp", os.pathsep.join(map(str, jars)), "-d", classes,
             *probes])
        run([runtime_java, "-Xmx256m", "-cp", os.pathsep.join(map(str, [classes, *jars])),
             "dev.tuink.compat.PortableEngineProbe"])
        if args.markdown:
            run([runtime_java, '-Xmx256m', '-cp', os.pathsep.join(map(str, [classes, *jars])),
                 'dev.tuink.compat.PortableMarkdownApiProbe'])
        probe = work / "probe.jar"
        with zipfile.ZipFile(probe, "w", zipfile.ZIP_DEFLATED) as archive:
            for path in sorted(classes.rglob("*.class")):
                archive.write(path, path.relative_to(classes))
        for compiler in ("D8", "R8"):
            target = output / f"portable-{compiler.lower()}.zip"
            arguments = [java, "-Xmx512m", "-cp", dexer, f"com.android.tools.r8.{compiler}",
                         "--release", "--min-api", args.min_api, "--lib", android, "--output", target]
            if compiler == "R8":
                arguments += ["--pg-conf", root / "verification/compatibility/android/keep.pro"]
                if args.markdown:
                    arguments += ['--pg-conf', root / 'verification/compatibility/android/markdown-keep.pro']
            run([*arguments, probe, *jars])
            with zipfile.ZipFile(target) as archive:
                if "classes.dex" not in archive.namelist():
                    raise RuntimeError(f"{compiler} produced no classes.dex")
            print(f"PASS: {compiler} -> {target}", flush=True)
    print("ART runtime, Android API availability, platform storage and UI lifecycle are not checked.")


if __name__ == "__main__":
    main()
