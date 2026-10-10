#!/usr/bin/env python3
"""Check a glibc Debian binary's ELF requirements without executing the binary."""

import argparse
import os
from pathlib import Path
import re
import subprocess


LIBRARY_PACKAGES = {
    "libc.so.6": ("libc6", "GLIBC_"),
    "libm.so.6": ("libc6", "GLIBC_"),
    "libpthread.so.0": ("libc6", "GLIBC_"),
    "libdl.so.2": ("libc6", "GLIBC_"),
    "librt.so.1": ("libc6", "GLIBC_"),
    "libresolv.so.2": ("libc6", "GLIBC_"),
    "libutil.so.1": ("libc6", "GLIBC_"),
    "libanl.so.1": ("libc6", "GLIBC_"),
    "libz.so.1": ("zlib1g", "ZLIB_"),
}


def command_output(*command):
    return subprocess.run(
        command, check=True, capture_output=True, text=True,
        env={**os.environ, "LC_ALL": "C"},
    ).stdout


def dependency_floors(control):
    fields = {}
    current = None
    for line in control.splitlines():
        if line.startswith((" ", "\t")) and current:
            fields[current] += " " + line.strip()
        elif ":" in line:
            current, value = line.split(":", 1)
            fields[current] = value.strip()
        else:
            current = None
    floors = {}
    for dependency in fields.get("Depends", "").split(","):
        match = re.fullmatch(r"\s*([a-z0-9+.-]+)\s*\(>=\s*([^\s)]+)\)\s*", dependency)
        if match:
            floors[match[1]] = match[2]
    return floors


def elf_requirements(dynamic, versions):
    libraries = set(re.findall(r"\(NEEDED\).*?\[([^]]+)\]", dynamic))
    requirements = {}
    in_needs = False
    library = None
    for line in versions.splitlines():
        if line.startswith("Version "):
            in_needs = line.startswith("Version needs section")
            library = None
        if not in_needs:
            continue
        match = re.search(r"\bFile:\s+(\S+)", line)
        if match:
            library = match[1]
        match = re.search(r"\bName:\s+(\S+)", line)
        if match and library:
            requirements.setdefault(library, set()).add(match[1])
    return libraries, requirements


def verify_dependencies(control, dynamic, versions):
    floors = dependency_floors(control)
    libraries, requirements = elf_requirements(dynamic, versions)
    if "libc.so.6" not in libraries:
        raise ValueError("The Debian package must contain a dynamically linked glibc binary")
    for library in sorted(libraries | requirements.keys()):
        if library not in LIBRARY_PACKAGES:
            raise ValueError(f"Undeclared shared library: {library}; add its package mapping and dependency")
        package, prefix = LIBRARY_PACKAGES[library]
        if package not in floors:
            raise ValueError(f"Missing minimum dependency for {package} ({library})")
        required_versions = []
        for symbol_version in requirements.get(library, ()):
            required = symbol_version.removeprefix(prefix)
            if not symbol_version.startswith(prefix) or not re.fullmatch(r"[0-9]+(?:\.[0-9]+)*", required):
                raise ValueError(f"Unsupported version requirement: {library} {symbol_version}")
            required_versions.append(required)
        if required_versions:
            required = max(required_versions, key=lambda value: tuple(map(int, value.split("."))))
            # An epoch affects Debian ordering, not the upstream ELF ABI version.
            declared = floors[package].split(":", 1)[-1].split("-", 1)[0]
            result = subprocess.run(["dpkg", "--compare-versions", declared, "ge", required])
            if result.returncode == 1:
                raise ValueError(
                    f"{library} requires {prefix}{required}, but Depends declares {package} >= {floors[package]}"
                )
            if result.returncode:
                raise ValueError(f"Cannot compare dependency versions for {package}")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--binary", type=Path, required=True)
    parser.add_argument("--control", type=Path, required=True)
    args = parser.parse_args()
    try:
        verify_dependencies(
            args.control.read_text(encoding="utf-8"),
            command_output("readelf", "--wide", "--dynamic", str(args.binary)),
            command_output("readelf", "--wide", "--version-info", str(args.binary)),
        )
    except (OSError, ValueError, subprocess.CalledProcessError) as error:
        parser.exit(1, f"Debian native dependency check failed: {error}\n")
    print("Debian native ELF requirements are covered by Depends")


if __name__ == "__main__":
    main()
