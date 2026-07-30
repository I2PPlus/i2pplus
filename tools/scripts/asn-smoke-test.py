#!/usr/bin/env python3
#
# I2P+ ASN / GeoIP Smoke Test
#
# Fetches the /profiles page and inspects the Host/Domain column
# for mangled values — single-character fragments, bare TLDs,
# and other signs of truncation in the reverse-lookup pipeline.
#
# Usage:
#   tools/scripts/asn-smoke-test.py
#   tools/scripts/asn-smoke-test.py --host 127.0.0.1 --port 7657
#   tools/scripts/asn-smoke-test.py --threshold 20   # max % mangled
#   tools/scripts/asn-smoke-test.py --verbose         # list every suspicious host
#   tools/scripts/asn-smoke-test.py --dump            # dump all host/domain values

import re
import sys
import json
import time
import argparse
import threading
import urllib.request
import urllib.error
import html.parser

HOST = "127.0.0.1"
PORT = 7657
PATH = "/profiles"
TIMEOUT = 30
MANGLE_THRESHOLD = 20  # fail if > this % of hosts are mangled


def spinner(evt, label):
    """Show a spinning indicator until the event is set."""
    chars = "⠋⠙⠹⠸⠼⠴⠦⠧⠇⠏"
    i = 0
    while not evt.is_set():
        sys.stdout.write(f"\r{chars[i]} {label} ...")
        sys.stdout.flush()
        i = (i + 1) % len(chars)
        time.sleep(0.08)
    sys.stdout.write(f"\r✔ {label}\n")
    sys.stdout.flush()


class RlookupCollector(html.parser.HTMLParser):
    """Parse .rlookup span elements and collect their text content + title."""

    def __init__(self):
        super().__init__()
        self._in_rlookup = False
        self._depth = 0
        self._buf = ""
        self.entries = []

    def handle_starttag(self, tag, attrs):
        attrs = dict(attrs)
        if tag == "span" and attrs.get("class") == "rlookup":
            self._in_rlookup = True
            self._depth = 1
            self._buf = ""
            self._title = attrs.get("title", "")
        elif self._in_rlookup:
            self._depth += 1

    def handle_endtag(self, tag):
        if self._in_rlookup:
            self._depth -= 1
            if self._depth == 0:
                self.entries.append((self._title, self._buf.strip()))
                self._in_rlookup = False

    def handle_data(self, data):
        if self._in_rlookup:
            self._buf += data


def fetch_profiles(host, port):
    url = f"http://{host}:{port}{PATH}"
    done = threading.Event()
    t = threading.Thread(target=spinner, args=(done, f"Fetching /profiles from {host}:{port}"), daemon=True)
    t.start()
    req = urllib.request.Request(url, headers={"User-Agent": "ASNSmokeTest/1.0"})
    try:
        with urllib.request.urlopen(req, timeout=TIMEOUT) as resp:
            html = resp.read().decode("utf-8", errors="replace")
        done.set()
        t.join()
        return html
    except (urllib.error.URLError, ConnectionError, OSError) as e:
        done.set()
        t.join()
        print(f"\nFAIL: Could not fetch {url}: {e}", file=sys.stderr)
        sys.exit(1)


def classify_host(title, text):
    """
    Classify a host/domain entry as OK or mangled.
    Returns (status, reason) where status is 'ok' or 'mangled'.
    """
    display = text.strip()
    full = title.strip()

    if not full and not display:
        return ("skip", "empty")

    # If both title and display are present, they should be similar length
    if full and display:
        ratio = len(display) / max(len(full), 1)
        # Display is just a fragment of the title
        if ratio < 0.3 and len(display) <= 3:
            return ("mangled", f"display '{display}' is fragment of full '{full}'")

    # Single character display (but not a known single-char org like "J J")
    if len(display) <= 2 and display.isalpha():
        return ("mangled", f"single-character host '{display}'")

    # Bare TLD (.au, .pl, .uk, etc.)
    if re.match(r'^\.\w{2,}$', display):
        return ("mangled", f"bare TLD '{display}'")

    # Contains only symbols or whitespace
    if re.match(r'^[\W_]+$', display):
        return ("mangled", f"symbols-only host '{display}'")

    # Suspiciously short - single word of 1-2 chars (but allow "au", "uk" etc with context)
    words = display.split()
    if len(words) == 1 and len(words[0]) <= 2 and words[0].isalpha():
        return ("mangled", f"single short word '{display}'")

    return ("ok", "")


def main():
    parser = argparse.ArgumentParser(description="ASN/GeoIP smoke test for mangled hostnames")
    parser.add_argument("--host", default=HOST, help=f"Router console host (default: {HOST})")
    parser.add_argument("--port", type=int, default=PORT, help=f"Router console port (default: {PORT})")
    parser.add_argument("--threshold", type=int, default=MANGLE_THRESHOLD,
                        help=f"Max percentage of mangled hosts before failing (default: {MANGLE_THRESHOLD})")
    parser.add_argument("--verbose", "-v", action="store_true", help="List every suspicious host")
    parser.add_argument("--dump", action="store_true", help="Dump all host/domain values")
    args = parser.parse_args()

    html = fetch_profiles(args.host, args.port)

    done = threading.Event()
    t = threading.Thread(target=spinner, args=(done, "Parsing host/domain entries"), daemon=True)
    t.start()
    collector = RlookupCollector()
    collector.feed(html)
    entries = collector.entries
    done.set()
    t.join()

    if not entries:
        print("FAIL: No .rlookup spans found on the profiles page", file=sys.stderr)
        sys.exit(2)

    total = len(entries)
    mangled = []
    ok_count = 0

    if args.dump:
        print("─" * 60)
        print("  ALL HOSTS")
        print("─" * 60)
        for title, text in entries:
            print(f"  title={title!r:40s} display={text!r}")
        print()

    done = threading.Event()
    t = threading.Thread(target=spinner, args=(done, f"Classifying {total} entries"), daemon=True)
    t.start()
    for title, text in entries:
        status, reason = classify_host(title, text)
        if status == "mangled":
            mangled.append((title, text, reason))
        elif status == "ok":
            ok_count += 1
    done.set()
    t.join()

    pct = (len(mangled) / total) * 100 if total else 0

    print()
    print("═" * 60)
    print("  ASN / GeoIP Smoke Test Summary")
    print("═" * 60)
    print(f"  Profiles scanned     {total}")
    print(f"  OK                   {ok_count}")
    print(f"  Mangled              {len(mangled)} ({pct:.1f}%)")
    print(f"  Threshold            {args.threshold}%")
    print()

    if mangled:
        print("─" * 60)
        print(f"  Top mangled entries ({min(len(mangled), 10)} shown)")
        print("─" * 60)
        for title, text, reason in mangled[:10]:
            print(f"  • {text!r:20s}  →  {reason}")
            print(f"    title={title!r}")
        print()
        if args.verbose and len(mangled) > 10:
            for title, text, reason in mangled[10:]:
                print(f"  • {text!r:20s}  →  {reason}")
                print(f"    title={title!r}")
            print()

    if pct > args.threshold:
        print(f"  ⚠  FAIL — {pct:.1f}% mangled exceeds threshold {args.threshold}%")
        print()
        sys.exit(1)

    if mangled:
        print(f"  ⚠  WARNING — {len(mangled)} mangled hosts (within {args.threshold}% threshold)")
        print()
        sys.exit(0)

    print("  ✔  PASS — All hosts look reasonable")
    print()


if __name__ == "__main__":
    main()
