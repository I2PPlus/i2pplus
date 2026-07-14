#!/usr/bin/env python3
"""
SAM Bridge smoke test — runs against a running SAM instance.

Tests the following SAM subsystems:

  Control channel  – HELLO handshake, protocol version negotiation
  Key generation   – DEST GENERATE (transient keypair)
  Naming service   – NAMING LOOKUP (addressbook resolution)
  Session mgmt     – SESSION CREATE STREAM / DATAGRAM / RAW (TRANSIENT)

Each session type uses its own socket (SAM allows one session per socket).

Returns exit code 0 on success, 1 on failure.
"""

import os
import random
import socket
import sys


GOOD = 0
BAD = 0


def recv_line(sock, timeout=120):
    sock.settimeout(timeout)
    buf = b""
    while True:
        try:
            c = sock.recv(1)
        except socket.timeout:
            return None
        if not c:
            return None
        if c == b"\n":
            break
        buf += c
    return buf.decode()


def check(label, ok, detail=""):
    global GOOD, BAD
    if ok:
        GOOD += 1
        print("  [OK] {}".format(label))
    else:
        BAD += 1
        print("  [FAIL] {} {}".format(label, detail))


def make_session(host, port, style, sid, timeout=120):
    """Open a fresh socket, HELLO, SESSION CREATE with given style. Return (socket, dest)."""
    s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    s.settimeout(timeout)
    s.connect((host, port))
    s.sendall(b"HELLO VERSION MIN=3.0 MAX=3.3\n")
    assert "RESULT=OK" in recv_line(s, 15), "HELLO failed"
    s.sendall("SESSION CREATE STYLE={} ID={} DESTINATION=TRANSIENT\n".format(style, sid).encode())
    resp = recv_line(s)
    if resp is None or "RESULT=OK" not in resp:
        s.close()
        return None, resp
    dest = ""
    if "DESTINATION=" in resp:
        dest = resp.split("DESTINATION=")[1].split()[0]
    return s, dest


def section(title):
    print()
    print("--- {} ---".format(title))


def main():
    host = sys.argv[1] if len(sys.argv) >= 2 else "127.0.0.1"
    port = int(sys.argv[2]) if len(sys.argv) >= 3 else 7656
    tag = "{:05x}".format(random.randrange(0, 0xfffff))

    print()
    print("SAM Bridge smoke test")
    print("  target : {}:{}".format(host, port))
    print("  tag    : {}".format(tag))
    print("  " + "-" * 48)

    # ── Control channel ──────────────────────────────────────────────────────
    section("Control channel")
    s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    s.settimeout(15)
    try:
        s.connect((host, port))
    except Exception as e:
        print("  FAIL: connect — {}".format(e))
        sys.exit(1)

    s.sendall(b"HELLO VERSION MIN=3.0 MAX=3.3\n")
    resp = recv_line(s)
    if resp and "RESULT=OK" in resp and "VERSION=" in resp:
        ver = resp.split("VERSION=")[1]
    else:
        ver = None
    check("HELLO  protocol version negotiation",
          resp and "RESULT=OK" in resp, "SAM {}".format(ver or "?"))
    s.close()

    # ── Key generation ───────────────────────────────────────────────────────
    section("Key generation")
    d = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    d.settimeout(60)
    d.connect((host, port))
    d.sendall(b"HELLO VERSION MIN=3.0 MAX=3.3\n")
    recv_line(d)
    d.sendall(b"DEST GENERATE\n")
    resp = recv_line(d)
    if resp and "DEST REPLY" in resp:
        dest = resp.split("DEST REPLY")[1].strip()
        n_bytes = len(dest)
    else:
        dest = ""
        n_bytes = 0
    check("DEST GENERATE  transient keypair  {} byte dest".format(n_bytes),
          resp and "DEST REPLY" in resp,
          "FAILED" if not resp else "")
    d.close()

    # ── Naming service ───────────────────────────────────────────────────────
    section("Naming service (addressbook / netdb)")
    n = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    n.settimeout(60)
    n.connect((host, port))
    n.sendall(b"HELLO VERSION MIN=3.0 MAX=3.3\n")
    recv_line(n)
    n.sendall(b"NAMING LOOKUP NAME=skank.i2p\n")
    resp = recv_line(n, 30)
    if resp and "RESULT=OK" in resp:
        dest_chunk = resp.split("DESTINATION=")[1].split()[0] if "DESTINATION=" in resp else ""
        n_bytes = len(dest_chunk)
    else:
        dest_chunk = ""
        n_bytes = 0
    check("NAMING LOOKUP  skank.i2p  {} byte dest".format(n_bytes),
          resp and "RESULT=OK" in resp,
          "FAILED" if not resp else "")
    n.close()

    # ── Session management ───────────────────────────────────────────────────
    section("Session management (TRANSIENT)")

    for style in ("STREAM", "DATAGRAM", "RAW"):
        sock, dest = make_session(host, port, style, style[0].lower() + tag)
        n_bytes = len(dest) if dest else 0
        check("SESSION CREATE  {}  {} byte dest".format(style, n_bytes),
              sock is not None,
              "FAILED" if sock is None else "")
        if sock:
            sock.close()

    # ── Summary ──────────────────────────────────────────────────────────────
    print()
    print("  " + "=" * 48)
    print("  Results:  {} passed  /  {} failed".format(GOOD, BAD))
    print("  " + "=" * 48)
    sys.exit(0 if BAD == 0 else 1)


if __name__ == "__main__":
    main()
