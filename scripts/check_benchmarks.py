#!/usr/bin/env python3
"""Checks the benchmark result files against their schema and the README tables.

Every `<implementation>/benchmarks/results/*.json` must be a valid suite, every value in it must
appear in a README table row with the same label, and every table in a README's Performance section
must have a counterpart. `benchmarks/highlights.json` must match the root README.

Usage: python3 scripts/check_benchmarks.py
"""

from __future__ import annotations

import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
IMPLEMENTATIONS = {"dotnet": "dotnet", "rust": "rust", "python": "python", "nodejs": "node", "go": "go", "java": "java", "php": "php"}
UNITS = {"ms", "s", "MB", "KB", "rows/s"}
MEMORY_KINDS = {"allocated", "peakRss", "peakHeap", "wasmMemory"}
SCALE = {("s", "ms"): 1000, ("ms", "s"): 0.001, ("MB", "KB"): 1000, ("KB", "MB"): 0.001}
SUITE_KEYS = {
    "id": str, "language": str, "group": str, "title": str, "dataset": str, "method": str, "environment": dict,
    "versions": list, "metrics": list, "chartMetric": str, "rows": list, "notes": list, "reproduce": str,
    "sourcePath": str,
}
REQUIRED = {"id", "language", "title", "dataset", "method", "environment", "versions", "metrics", "rows", "notes", "sourcePath"}
NUMBER = re.compile(r"(\d[\d,]*(?:\.\d+)?)\s*(ms|s|MB|KB)?(?![\w.])")


def normalize(label: str) -> str:
    label = re.sub(r"[*`\\]", "", label)
    return re.sub(r"\s+", " ", label).strip().lower()


def tables(markdown: str) -> list[list[list[str]]]:
    """Returns the body rows (cells without the header and separator) of every table."""
    found, current = [], []
    for line in markdown.splitlines() + [""]:
        if line.startswith("|"):
            current.append([c.strip() for c in line.strip().strip("|").split("|")])
        elif current:
            if len(current) > 2:
                found.append(current[2:])
            current = []
    return found


def performance_section(markdown: str) -> str:
    match = re.search(r"^## Performance\n(.*?)(?=^## )", markdown, re.S | re.M)
    return match.group(1) if match else ""


def cell_values(cell: str, unit: str) -> list[float]:
    values = []
    for number, cell_unit in NUMBER.findall(cell.replace("**", "").replace("~", "")):
        value = float(number.replace(",", ""))
        if cell_unit and cell_unit != unit:
            if (cell_unit, unit) not in SCALE:
                continue
            value *= SCALE[(cell_unit, unit)]
        values.append(value)
    return values


def check_suite(path: Path, suite: dict, errors: list[str], matched: set[tuple[str, int]]) -> None:
    def fail(message: str) -> None:
        errors.append(f"{path.relative_to(ROOT)}: {message}")

    for key in REQUIRED - suite.keys():
        fail(f"missing {key}")
    for key, value in suite.items():
        if key not in SUITE_KEYS:
            fail(f"unknown key {key}")
        elif not isinstance(value, SUITE_KEYS[key]):
            fail(f"{key} must be {SUITE_KEYS[key].__name__}")
    if errors and errors[-1].startswith(str(path.relative_to(ROOT))):
        return
    if suite["language"] not in IMPLEMENTATIONS:
        fail(f"unknown language {suite['language']}")
        return
    if path.parent != ROOT / IMPLEMENTATIONS[suite["language"]] / "benchmarks" / "results":
        fail("file is not in its implementation's benchmarks/results directory")
    if path.stem != suite["id"]:
        fail("file name must be the suite id")
    if not {"hardware", "runtime"} <= suite["environment"].keys():
        fail("environment needs hardware and runtime")
    if not (ROOT / suite["sourcePath"]).exists():
        fail(f"sourcePath {suite['sourcePath']} does not exist")

    metrics = {}
    for metric in suite["metrics"]:
        if metric.get("unit") not in UNITS:
            fail(f"metric {metric.get('id')} has unit {metric.get('unit')!r}")
        if not isinstance(metric.get("lowerIsBetter"), bool):
            fail(f"metric {metric.get('id')} needs lowerIsBetter")
        if metric.get("unit") in {"MB", "KB"} and not str(metric.get("id")).startswith("file") and metric.get("memoryKind") not in MEMORY_KINDS:
            fail(f"metric {metric.get('id')} needs a memoryKind ({', '.join(sorted(MEMORY_KINDS))})")
        if metric.get("id") in metrics:
            fail(f"duplicate metric {metric.get('id')}")
        metrics[metric.get("id")] = metric
    if not metrics or not suite["rows"]:
        fail("needs at least one metric and one row")
    if "chartMetric" in suite and suite["chartMetric"] not in metrics:
        fail(f"unknown chartMetric {suite['chartMetric']}")

    for row in suite["rows"]:
        if not isinstance(row.get("subject"), bool):
            fail(f"row {row.get('label')!r} needs subject")
        for key in row.get("values", {}):
            if key not in metrics:
                fail(f"row {row.get('label')!r} has unknown metric {key}")

    readme = ROOT / IMPLEMENTATIONS[suite["language"]] / "README.md"
    best: list[str] | None = None
    for index, table in enumerate(tables(performance_section(readme.read_text()))):
        problems = table_mismatches(table, suite["rows"], metrics)
        if not problems:
            matched.add((suite["language"], index))
            return
        if best is None or len(problems) < len(best):
            best = problems
    for problem in best or ["no table in the Performance section"]:
        fail(f"{problem} in {readme.relative_to(ROOT)}")


def table_mismatches(table: list[list[str]], rows: list[dict], metrics: dict) -> list[str]:
    """Lists the rows whose label or values are not in this README table."""
    by_label: dict[str, list[str]] = {}
    for cells in table:
        by_label[normalize(cells[0])] = cells[1:]
        if len(cells) > 2 and not re.search(r"\d", cells[1]):
            by_label[normalize(f"{cells[0]} ({cells[1]})")] = cells[2:]
    problems = []
    for row in rows:
        label = row.get("label", "")
        cells = by_label.get(normalize(label))
        if cells is None:
            problems.append(f"row {label!r} is not")
            continue
        for key, value in row.get("values", {}).items():
            if key in metrics and value is not None and not any(
                abs(found - value) <= 1e-9 * max(1, abs(value)) for cell in cells for found in cell_values(cell, metrics[key]["unit"])
            ):
                problems.append(f"row {label!r}: {key} = {value} {metrics[key]['unit']} is not")
    return problems


def check_coverage(matched: set[tuple[str, int]], errors: list[str]) -> None:
    for language, directory in IMPLEMENTATIONS.items():
        readme = ROOT / directory / "README.md"
        for index, table in enumerate(tables(performance_section(readme.read_text()))):
            if (language, index) not in matched:
                errors.append(f"{readme.relative_to(ROOT)}: table starting with {table[0][0]!r} has no results file")


def check_highlights(errors: list[str]) -> None:
    path = ROOT / "benchmarks" / "highlights.json"
    readme = (ROOT / "README.md").read_text()
    glance = readme[readme.index("## At a glance"):readme.index("## Quick look")].replace("`", "")
    try:
        highlights = json.loads(path.read_text())
    except (OSError, ValueError) as error:
        errors.append(f"{path.relative_to(ROOT)}: {error}")
        return
    for item in highlights:
        name = f"{path.relative_to(ROOT)}: {item.get('language')} {item.get('group', 'writing')}"
        for key in ("language", "value", "label", "comparison"):
            if not isinstance(item.get(key), str):
                errors.append(f"{name}: needs {key}")
        if item.get("language") not in IMPLEMENTATIONS:
            errors.append(f"{name}: unknown language")
        if "memory" in item and item.get("memoryKind") not in MEMORY_KINDS:
            errors.append(f"{name}: memory needs a memoryKind")
        for key in ("value", "comparison", "memory"):
            if key in item and item[key] not in glance:
                errors.append(f"{name}: {key} {item[key]!r} is not in the README's At a glance section")


def main() -> int:
    errors: list[str] = []
    suites, ids, matched = [], set(), set()
    for path in sorted(ROOT.glob("*/benchmarks/results/*.json")):
        try:
            suite = json.loads(path.read_text())
        except ValueError as error:
            errors.append(f"{path.relative_to(ROOT)}: {error}")
            continue
        if not isinstance(suite, dict):
            errors.append(f"{path.relative_to(ROOT)}: must be an object")
            continue
        check_suite(path, suite, errors, matched)
        if suite.get("id") in ids:
            errors.append(f"{path.relative_to(ROOT)}: duplicate suite id {suite.get('id')}")
        ids.add(suite.get("id"))
        suites.append(suite)
    check_coverage(matched, errors)
    check_highlights(errors)
    for error in errors:
        print(error, file=sys.stderr)
    print(f"{len(suites)} suites, {len(errors)} problems")
    return 1 if errors else 0


if __name__ == "__main__":
    sys.exit(main())
