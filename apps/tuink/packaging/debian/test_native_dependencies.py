#!/usr/bin/env python3
"""Regression cases for native Debian dependency validation."""

import unittest

from verify_native_dependencies import verify_dependencies


CONTROL = "Depends: libc6 (>= 2.34), zlib1g (>= 1:1.2.2)\n"
DYNAMIC = """Dynamic section at offset 0x123 contains 3 entries:
 0x0000000000000001 (NEEDED) Shared library: [libc.so.6]
 0x0000000000000001 (NEEDED) Shared library: [libz.so.1]
"""
VERSIONS = """Version symbols section '.gnu.version' contains 2 entries:
  000: 1 (*global*) 2 (GLIBC_2.99)
Version needs section '.gnu.version_r' contains 2 entries:
  000000: Version: 1 File: libz.so.1 Cnt: 2
  0x0010: Name: ZLIB_1.2.2 Flags: none Version: 4
  0x0020: Name: ZLIB_1.2.0 Flags: none Version: 3
  0x0030: Version: 1 File: libc.so.6 Cnt: 2
  0x0040: Name: GLIBC_2.34 Flags: none Version: 2
  0x0050: Name: GLIBC_2.2.5 Flags: none Version: 1
"""


class NativeDependenciesTest(unittest.TestCase):
    def test_current_abi_is_covered_and_ignores_the_symbol_display(self):
        verify_dependencies(CONTROL, DYNAMIC, VERSIONS)

    def test_understated_zlib_floor_is_rejected(self):
        with self.assertRaisesRegex(ValueError, "requires ZLIB_1.2.2"):
            verify_dependencies(CONTROL.replace("1:1.2.2", "1:1.1.4"), DYNAMIC, VERSIONS)

    def test_newer_glibc_requirement_is_rejected(self):
        with self.assertRaisesRegex(ValueError, "requires GLIBC_2.35"):
            verify_dependencies(CONTROL, DYNAMIC, VERSIONS.replace("GLIBC_2.34", "GLIBC_2.35"))

    def test_newer_zlib_requirement_is_rejected(self):
        with self.assertRaisesRegex(ValueError, "requires ZLIB_1.2.10"):
            verify_dependencies(CONTROL, DYNAMIC, VERSIONS.replace("ZLIB_1.2.2", "ZLIB_1.2.10"))

    def test_additional_shared_library_is_rejected(self):
        with self.assertRaisesRegex(ValueError, "Undeclared shared library: libstdc"):
            verify_dependencies(
                CONTROL, DYNAMIC + " (NEEDED) Shared library: [libstdc++.so.6]\n", VERSIONS
            )

    def test_missing_dependency_is_rejected(self):
        with self.assertRaisesRegex(ValueError, "Missing minimum dependency for zlib1g"):
            verify_dependencies("Depends: libc6 (>= 2.34)\n", DYNAMIC, VERSIONS)

    def test_unknown_abi_requirement_is_rejected(self):
        with self.assertRaisesRegex(ValueError, "Unsupported version requirement"):
            verify_dependencies(CONTROL, DYNAMIC, VERSIONS.replace("GLIBC_2.34", "GLIBC_PRIVATE"))

    def test_static_binary_is_not_a_debian_glibc_binary(self):
        with self.assertRaisesRegex(ValueError, "dynamically linked glibc"):
            verify_dependencies(CONTROL, "There is no dynamic section in this file.", "")

    def test_upstream_version_ordering_and_folded_metadata(self):
        verify_dependencies(
            "Depends: libc6 (>= 2.34),\n zlib1g (>= 1:1.2.10-1)\n",
            DYNAMIC, VERSIONS,
        )


if __name__ == "__main__":
    unittest.main()
