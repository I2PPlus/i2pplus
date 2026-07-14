#!/usr/bin/env python3
"""
Simple echo server — accepts one TCP connection, reads lines, echoes them back.

Usage:  ./server.py [port]
          port : listen port (default: 25000)
"""

import socket
import sys


def main():
    port = int(sys.argv[1]) if len(sys.argv) >= 2 else 25000

    serversocket = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    serversocket.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    serversocket.bind(("0.0.0.0", port))
    serversocket.listen(1)
    print("Listening on port", port)

    clientsocket, address = serversocket.accept()
    print("Accepted from", address)

    i = 0
    while True:
        chunk = clientsocket.recv(1024)
        if not chunk:
            break
        i += 1
        line = chunk.decode().strip()
        print("{} {}".format(i, line))
        clientsocket.sendall("{} {}\n".format(i, line).encode())

    clientsocket.close()
    print("Done")


if __name__ == "__main__":
    main()
