#!/usr/bin/env python3
"""Fail if PR line coverage dropped more than TOLERANCE pp below main's baseline.

Inputs:
    build/reports/jacoco/test/jacocoTestReport.xml   - PR's coverage XML
    coverage-baseline.txt                            - main's last recorded ratio,
                                                       restored from GitHub Actions cache

Behavior:
    - If baseline file is missing or empty (no prior main run cached, or cache
      expired), the check passes with a WARNING — we don't want PRs blocked
      on baseline bootstrap problems.
    - Otherwise, compute drop = baseline - pr_ratio. Fail if drop > TOLERANCE.
"""
from __future__ import annotations

import sys
import xml.etree.ElementTree as ET
from pathlib import Path

TOLERANCE_PP = 0.05  # 5 percentage points
JACOCO_XML = Path("build/reports/jacoco/test/jacocoTestReport.xml")
BASELINE = Path("coverage-baseline.txt")


def line_coverage(xml_path: Path) -> float:
    root = ET.parse(xml_path).getroot()
    for counter in root.findall("counter"):
        if counter.attrib.get("type") == "LINE":
            covered = int(counter.attrib["covered"])
            missed = int(counter.attrib["missed"])
            total = covered + missed
            return covered / total if total else 0.0
    raise ValueError(f"No LINE counter in {xml_path}")


def main() -> int:
    pr_ratio = line_coverage(JACOCO_XML)

    baseline_text = BASELINE.read_text().strip() if BASELINE.is_file() else ""
    if not baseline_text:
        print(f"::warning::No coverage baseline from main found.")
        print(f"PR coverage: {pr_ratio:.2%}. Skipping delta check.")
        print("This is normal on the first run after the baseline cache expired or never existed.")
        return 0

    baseline = float(baseline_text)
    drop_pp = (baseline - pr_ratio) * 100

    print(f"PR coverage:    {pr_ratio:.2%}")
    print(f"Main baseline:  {baseline:.2%}")
    print(f"Delta:          {-drop_pp:+.2f} pp")
    print(f"Tolerance:      {-TOLERANCE_PP * 100:.2f} pp")

    if drop_pp > TOLERANCE_PP * 100:
        print(
            f"::error::Coverage dropped {drop_pp:.2f} pp from main "
            f"(tolerance is {TOLERANCE_PP * 100:.0f} pp)."
        )
        return 1

    print("OK")
    return 0


if __name__ == "__main__":
    sys.exit(main())
