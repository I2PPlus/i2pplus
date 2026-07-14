#!/usr/bin/env python3
"""
Open a STREAM session, connect to an I2P destination looked up via naming,
then read data from the connection.

Usage:  ./samOutWithNaming.py [name] [url]
          name : session nickname (default: testOutStream)
          url  : I2P URL to connect to (default: http://amiga.i2p)
"""

import socket
import sys


def recv_line(sock):
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


def main():
    name = sys.argv[1] if len(sys.argv) >= 2 else "testOutStream"
    url = sys.argv[2] if len(sys.argv) >= 3 else "http://amiga.i2p"

    sess = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    sess.settimeout(120)
    sess.connect(("127.0.0.1", 7656))
    sess.sendall(b"HELLO VERSION MIN=3.0 MAX=3.3\n")
    resp = recv_line(sess)
    print("HELLO:", resp)
    if "RESULT=OK" not in (resp or ""):
        sys.exit(1)

    sess.sendall(
        "SESSION CREATE STYLE=STREAM ID={} DESTINATION=TRANSIENT\n".format(name).encode()
    )
    resp = recv_line(sess)
    if "RESULT=OK" not in (resp or ""):
        print("SESSION CREATE error:", resp)
        sys.exit(1)

    sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    sock.settimeout(120)
    sock.connect(("127.0.0.1", 7656))
    sock.sendall(b"HELLO VERSION MIN=3.0 MAX=3.3\n")
    recv_line(sock)
    sock.sendall(
        "STREAM CONNECT ID={} DESTINATION={} SILENT=false\n".format(name, url).encode()
    )
    status = recv_line(sock)
    print("CONNECT:", status)
    if "RESULT=OK" not in (status or ""):
        sys.exit(1)

    while True:
        buf = sock.recv(4096)
        if not buf:
            break
        sys.stdout.write(buf.decode(errors="replace"))
    print()


if __name__ == "__main__":
    main()
