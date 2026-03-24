#!/usr/bin/env python3
"""
Fix PMD violations that can be automated.

Usage: fix-pmd.py -p <directory> [--dry-run]

Fixes:
  - None currently (StringBuffer removed — can break type declarations)
"""

import sys
import os
import re
import argparse


def find_java_files(scan_path):
    """Find all .java files under scan_path."""
    files = []
    if os.path.isfile(scan_path) and scan_path.endswith(".java"):
        files.append(scan_path)
    elif os.path.isdir(scan_path):
        for root, dirs, filenames in os.walk(scan_path):
            for f in filenames:
                if f.endswith(".java"):
                    files.append(os.path.join(root, f))
    return files


def main():
    parser = argparse.ArgumentParser(
        description="Fix automated PMD violations.",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="Example:\n"
               "  fix-pmd.py -p core/java/src/net/i2p --dry-run\n"
               "  fix-pmd.py -p router/java/src")
    parser.add_argument("-p", "--path", required=True,
                        help="Directory to scan and fix")
    parser.add_argument("-n", "--dry-run", action="store_true",
                        help="Preview changes without modifying files")
    args = parser.parse_args()

    scan_path = os.path.abspath(args.path)
    if not os.path.exists(scan_path):
        print(f"ERROR: {scan_path} not found", file=sys.stderr)
        sys.exit(1)

    if args.dry_run:
        print("DRY RUN - no files will be modified\n")

    dry_label = " (dry run)" if args.dry_run else ""

    print(f"\nTotal: 0 lines fixed{dry_label}", file=sys.stderr)


if __name__ == "__main__":
    main()
