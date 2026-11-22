"""Release smoke test: run where the built wheel is installed, outside the source tree.

Writes a workbook with the native extension and reads it back, so a missing or unloadable
extension fails.
"""

import platform
import sys

import hypaxel

data = hypaxel.to_xlsx([{"id": 1, "name": "Ada"}], sheet_name="Smoke")
rows = hypaxel.read_rows(data, "Smoke")
expected = [["id", "name"], [1, "Ada"]]
if rows != expected:
    sys.exit(f"read back {rows!r}, expected {expected!r}")
print(f"hypaxel works on {platform.system()}-{platform.machine()} with Python {platform.python_version()}")
