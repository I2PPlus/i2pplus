#!/usr/bin/env python3
"""
Open a STREAM session, then connect to a peer destination and send messages.

Usage:  ./samOut.py <destination> [name]
          destination : base64 I2P destination to connect to
          name        : session nickname (default: testOutStream)

Example: ./samOut.py <dest-from-samIn.py>
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
    if len(sys.argv) < 2:
        print("Usage: {} <destination> [name]".format(sys.argv[0]))
        print("  destination : base64 I2P destination from samIn.py")
        print("  name        : session nickname (default: testOutStream)")
        sys.exit(1)

    dest = sys.argv[1]
    name = sys.argv[2] if len(sys.argv) >= 3 else "testOutStream"

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
    print("SESSION:", resp[:80] + "..." if resp and len(resp) > 80 else resp)
    if "RESULT=OK" not in (resp or ""):
        print("Session creation failed")
        sys.exit(1)

    sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    sock.settimeout(120)
    sock.connect(("127.0.0.1", 7656))
    sock.sendall(b"HELLO VERSION MIN=3.0 MAX=3.3\n")
    recv_line(sock)
    sock.sendall(
        "STREAM CONNECT ID={} DESTINATION={} SILENT=false\n".format(name, dest).encode()
    )
    # First line is the connection status
    status = recv_line(sock)
    print("CONNECT:", status)
    if "RESULT=OK" not in (status or ""):
        print("Connection failed")
        sys.exit(1)

    for i in range(1, 11):
        msg = "{}\n".format(i).encode()
        sock.sendall(msg)
        buf = sock.recv(1024)
        print("{} {}".format(i, buf.decode().strip()))
        if not buf:
            break
    print("Done")


if __name__ == "__main__":
    main()
