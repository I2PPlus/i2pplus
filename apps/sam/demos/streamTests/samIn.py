#!/usr/bin/env python3
"""
Create a STREAM session with TRANSIENT destination, print it,
then accept incoming streams and echo back received data.

Usage:  ./samIn.py [name]
          name : session nickname (default: inTest)
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
    name = sys.argv[1] if len(sys.argv) >= 2 else "inTest"

    sess = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    sess.settimeout(120)
    sess.connect(("127.0.0.1", 7656))
    sess.sendall(b"HELLO VERSION MIN=3.0 MAX=3.3\n")
    resp = recv_line(sess)
    print("HELLO:", resp)
    if "RESULT=OK" not in resp:
        sys.exit(1)

    sess.sendall(
        "SESSION CREATE STYLE=STREAM ID={} DESTINATION=TRANSIENT\n".format(
            name
        ).encode()
    )
    resp = recv_line(sess)
    if resp is None or "RESULT=OK" not in resp:
        print("SESSION CREATE error:", resp)
        sys.exit(1)

    dest = resp.split("DESTINATION=")[1].split()[0]
    print("Listening on:", dest)
    print("Share this destination with samOut.py")
    print("")

    while True:
        sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        sock.settimeout(120)
        sock.connect(("127.0.0.1", 7656))
        sock.sendall(b"HELLO VERSION MIN=3.0 MAX=3.3\n")
        recv_line(sock)
        sock.sendall("STREAM ACCEPT ID={} SILENT=false\n".format(name).encode())
        # First line is the connecting peer's destination
        peer = recv_line(sock)
        print("Accepted from:", peer[:60] + "..." if len(peer or "") > 60 else peer)
        try:
            while True:
                chunk = sock.recv(4096)
                if not chunk:
                    break
                print("Received:", chunk.decode(errors="replace").strip())
                sock.sendall(chunk)
        except (ConnectionResetError, BrokenPipeError):
            pass
        print("Connection closed")


if __name__ == "__main__":
    main()
