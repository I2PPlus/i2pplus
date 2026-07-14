#!/usr/bin/env python3
"""
Create a DATAGRAM session and listen for incoming datagrams on a local UDP port.

Usage:  ./samIn.py [port] [name]
          port : local UDP listen port (default: 25000)
          name : session nickname (default: datagramSamIn)

Example: ./samIn.py 25000 myIn
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
    listen_port = int(sys.argv[1]) if len(sys.argv) >= 2 else 25000
    name = sys.argv[2] if len(sys.argv) >= 3 else "datagramSamIn"

    sess = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    sess.settimeout(120)
    sess.connect(("127.0.0.1", 7656))
    sess.sendall(b"HELLO VERSION MIN=3.0 MAX=3.3\n")
    resp = recv_line(sess)
    print("HELLO:", resp)
    if "RESULT=OK" not in (resp or ""):
        sys.exit(1)

    sess.sendall(
        "SESSION CREATE STYLE=DATAGRAM ID={} DESTINATION=TRANSIENT"
        " PORT={}\n".format(name, listen_port).encode()
    )
    resp = recv_line(sess)
    if "RESULT=OK" not in (resp or ""):
        print("SESSION CREATE error:", resp)
        sys.exit(1)

    dest = resp.split("DESTINATION=")[1].split()[0]
    print("Listening on destination:", dest)
    print("Share this destination with samOut.py")

    udp = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    udp.bind(("127.0.0.1", listen_port))
    print("Waiting for datagrams on UDP port", listen_port)

    while True:
        data, addr = udp.recvfrom(65535)
        sender, payload = data.split(b"\n", 1)
        print("From {}: {}".format(addr, payload.decode(errors="replace")))


if __name__ == "__main__":
    main()
