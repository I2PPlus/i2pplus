#!/usr/bin/env python3
"""
Send a DATAGRAM message to a peer via the SAM v3 UDP server.

Usage:  ./samOut.py <destination> [name] [message]
          destination : base64 I2P destination from samIn.py
          name        : sending session nickname (default: datagramSamForward)
          message     : payload text (default: "This is a nice message")

Example: ./samOut.py <dest-from-samIn.py> myOut "hello world"
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
        print("Usage: {} <destination> [name] [message]".format(sys.argv[0]))
        sys.exit(1)

    dest = sys.argv[1]
    name = sys.argv[2] if len(sys.argv) >= 3 else "datagramSamForward"
    message = " ".join(sys.argv[3:]) if len(sys.argv) >= 4 else "This is a nice message"

    sess = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    sess.settimeout(120)
    sess.connect(("127.0.0.1", 7656))
    sess.sendall(b"HELLO VERSION MIN=3.0 MAX=3.3\n")
    resp = recv_line(sess)
    print("HELLO:", resp)
    if "RESULT=OK" not in (resp or ""):
        sys.exit(1)

    sess.sendall(
        "SESSION CREATE STYLE=DATAGRAM ID={} DESTINATION=TRANSIENT\n".format(name).encode()
    )
    resp = recv_line(sess)
    if "RESULT=OK" not in (resp or ""):
        print("SESSION CREATE error:", resp)
        sys.exit(1)

    udp = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    header = "3.3 {} {} \n".format(name, dest).encode()
    udp.sendto(header + message.encode(), ("127.0.0.1", 7655))
    print("Sent: {}".format(message))


if __name__ == "__main__":
    main()
