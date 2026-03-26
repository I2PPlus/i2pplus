#!/usr/bin/env python3
"""
Filter a SARIF report by exclusions from config/exclusions.txt.

Usage:
    filter-sarif.py input.sarif [output.sarif] [--exclude exclusions.txt]

If output is omitted, writes to stdout.
Default exclusion file: config/exclusions.txt
"""

import json
import os
import sys
import argparse
import fnmatch


def load_exclusions(path):
    """Load exclusions from config/exclusions.txt.
    Returns list of (source, rule, filepath, line) tuples."""
    entries = []
    if not os.path.exists(path):
        return entries
    with open(path, encoding="utf-8") as f:
        for raw in f:
            line = raw.strip()
            if not line or line.startswith("#"):
                continue
            parts = line.split(":", 3)
            if len(parts) < 3:
                continue
            source = parts[0].strip()
            rule = parts[1].strip()
            filepath = parts[2].strip()
            lineno = int(parts[3]) if len(parts) == 4 else None
            entries.append((source, rule, filepath, lineno))
    return entries


def is_excluded(result, exclusions, repo_root):
    """Check if a SARIF result matches any exclusion."""
    rule_id = result.get("ruleId", "")

    # Determine source from rule ID prefix
    if rule_id.startswith("java/"):
        source = "codeql"
    elif "/" in rule_id and rule_id[0].isupper():
        source = "pmd"
    elif "_" in rule_id and rule_id == rule_id.upper():
        source = "spotbugs"
    elif "." in rule_id:
        source = "checkstyle"
    else:
        source = "codeql"

    # Get file path from result
    locations = result.get("locations", [])
    if not locations:
        return False
    loc = locations[0].get("physicalLocation", {})
    uri = loc.get("artifactLocation", {}).get("uri", "")
    if uri.startswith("file://"):
        uri = uri[len("file://"):]
    rel = os.path.relpath(uri, repo_root).replace("\\", "/")

    # Get line number
    region = loc.get("region", {})
    line = region.get("startLine")

    for es, er, ef, el in exclusions:
        # Source match
        if es != "all" and es != source:
            continue
        # Rule match
        if er != "*" and er != rule_id:
            continue
        # File match
        if ef == "*":
            file_match = True
        elif "*" in ef:
            file_match = fnmatch.fnmatch(rel, ef)
        else:
            file_match = rel.endswith(ef) or rel == ef
        if not file_match:
            continue
        # Line match
        if el is not None and line is not None and el != line:
            continue
        return True
    return False


def main():
    parser = argparse.ArgumentParser(description="Filter SARIF report by exclusions")
    parser.add_argument("input", help="Input SARIF file")
    parser.add_argument("output", nargs="?", default=None, help="Output SARIF file (default: stdout)")
    parser.add_argument("-x", "--exclude", default=None,
                        help="Exclusion file (default: config/exclusions.txt)")
    args = parser.parse_args()

    script_dir = os.path.dirname(os.path.abspath(__file__))
    repo_root = os.path.dirname(os.path.dirname(script_dir))

    if args.exclude:
        exclude_path = args.exclude
    else:
        exclude_path = os.path.join(repo_root, "config", "exclusions.txt")

    exclusions = load_exclusions(exclude_path)
    print(f"Loaded {len(exclusions)} exclusions from {exclude_path}", file=sys.stderr)

    with open(args.input) as f:
        sarif = json.load(f)

    original_count = 0
    filtered_count = 0

    for run in sarif.get("runs", []):
        results = run.get("results", [])
        original_count += len(results)
        filtered = [r for r in results if not is_excluded(r, exclusions, repo_root)]
        filtered_count += len(filtered)
        run["results"] = filtered

    removed = original_count - filtered_count
    print(f"Filtered: {original_count} → {filtered_count} ({removed} removed)", file=sys.stderr)

    output = json.dumps(sarif, indent=2)
    if args.output:
        with open(args.output, "w") as f:
            f.write(output)
        print(f"Written to {args.output}", file=sys.stderr)
    else:
        print(output)


if __name__ == "__main__":
    main()
