#!/usr/bin/env python3
"""
Create a STREAM session and forward all incoming I2P streams to a local TCP server.

Usage:  ./samForward.py [port] [name] [host]
          port : local TCP port to forward to (default: 25000)
          name : session nickname (default: forward)
          host : local host to forward to  (default: 127.0.0.1)

Example: ./samForward.py 8080 myForward
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
    port = int(sys.argv[1]) if len(sys.argv) >= 2 else 25000
    name = sys.argv[2] if len(sys.argv) >= 3 else "forward"
    host = sys.argv[3] if len(sys.argv) >= 4 else "127.0.0.1"

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

    dest = resp.split("DESTINATION=")[1].split()[0]
    print("Forwarding from:", dest)
    print("Forwarding to: {}:{}".format(host, port))
    print("Start your TCP server on {}:{} then run samOut.py with the destination above".format(host, port))

    sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    sock.settimeout(120)
    sock.connect(("127.0.0.1", 7656))
    sock.sendall(b"HELLO VERSION MIN=3.0 MAX=3.3\n")
    recv_line(sock)
    sock.sendall(
        "STREAM FORWARD ID={} HOST={} PORT={}\n".format(name, host, port).encode()
    )
    status = recv_line(sock)
    print("FORWARD:", status)
    sys.stdout.flush()

    while True:
        chunk = sock.recv(1024)
        if not chunk:
            break
    print("Forward socket closed")


if __name__ == "__main__":
    main()
