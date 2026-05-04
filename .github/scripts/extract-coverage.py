#!/usr/bin/env python3
"""Extract line-coverage ratio from a JaCoCo XML report.

Usage:
    python3 extract-coverage.py [path/to/jacocoTestReport.xml]

Defaults to build/reports/jacoco/test/jacocoTestReport.xml (Gradle layout).
Prints the ratio as a 4-decimal float on stdout, e.g. "0.8311".
"""
from __future__ import annotations

import sys
import xml.etree.ElementTree as ET

DEFAULT_PATH = "build/reports/jacoco/test/jacocoTestReport.xml"


def main() -> int:
    path = sys.argv[1] if len(sys.argv) > 1 else DEFAULT_PATH
    root = ET.parse(path).getroot()
    for counter in root.findall("counter"):
        if counter.attrib.get("type") == "LINE":
            covered = int(counter.attrib["covered"])
            missed = int(counter.attrib["missed"])
            total = covered + missed
            ratio = covered / total if total else 0.0
            print(f"{ratio:.4f}")
            return 0
    print("ERROR: no LINE counter found in JaCoCo XML", file=sys.stderr)
    return 1


if __name__ == "__main__":
    sys.exit(main())
